package io.unisondroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.unisondroid.app.MainActivity
import io.unisondroid.app.data.HostKeyStore
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.KeystoreAesCipher
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.OutputParser
import io.unisondroid.app.sync.SshjTunnel
import io.unisondroid.app.sync.SyncEngine
import io.unisondroid.app.sync.SyncState
import io.unisondroid.app.sync.UnisonRunner
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import kotlin.math.roundToInt

class SyncService : LifecycleService() {

    private var observing = false
    private var syncInFlight = false
    private var sawActiveState = false
    private var stopped = false
    private var lastState: SyncState = SyncState.Idle

    override fun onCreate() {
        super.onCreate()
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId == null) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification("Sync failed: no profile given", ongoing = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            stopNow(removeNotification = true)
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(TEXT_RUNNING, ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        val engine = ServiceLocator.engine(applicationContext)
        val staleReplay = engine.state.value
        stopped = false
        sawActiveState = false
        lastState = SyncState.Idle
        syncInFlight = true
        if (!observing) {
            observing = true
            lifecycleScope.launch {
                var firstEmission = true
                engine.state.collect { state ->
                    if (firstEmission) {
                        firstEmission = false
                        if (state === staleReplay) return@collect
                    }
                    onState(state)
                }
            }
        }
        lifecycleScope.launch {
            val accepted = try {
                engine.requestSync(profileId)
            } catch (t: Throwable) {
                false
            }
            syncInFlight = false
            if (!accepted && !isActiveSync(engine.state.value)) {
                // Nothing is running to attach to, so don't linger in the
                // foreground with a stale "Syncing…" notification.
                stopNow(removeNotification = true)
                return@launch
            }
            maybeStop()
        }
        return START_NOT_STICKY
    }

    private fun isActiveSync(state: SyncState): Boolean =
        state is SyncState.Connecting ||
            state is SyncState.Syncing ||
            state is SyncState.AwaitingHostKey

    private fun onState(state: SyncState) {
        if (stopped) return
        lastState = state
        when (state) {
            SyncState.Idle -> Unit
            is SyncState.Connecting -> {
                sawActiveState = true
                post(TEXT_CONNECTING, ongoing = true)
            }

            is SyncState.AwaitingHostKey -> {
                sawActiveState = true
                post(TEXT_AWAITING_HOST_KEY, ongoing = true)
            }

            is SyncState.Syncing -> {
                sawActiveState = true
                post("Syncing… ${(state.progress * 100).roundToInt()}%", ongoing = true)
            }

            is SyncState.Finished -> post(
                "Sync finished: ${state.summary.transferred} transferred, " +
                    "${state.summary.failed} failed, ${state.summary.conflicts} conflicts",
                ongoing = false,
            )

            is SyncState.Failed -> post("Sync failed: ${reasonLabel(state.reason)}", ongoing = false)
        }
        maybeStop()
    }

    private fun maybeStop() {
        if (stopped || syncInFlight) return
        val terminal = lastState is SyncState.Finished ||
            lastState is SyncState.Failed ||
            (lastState == SyncState.Idle && sawActiveState)
        if (terminal) stopNow(removeNotification = false)
    }

    private fun stopNow(removeNotification: Boolean) {
        if (stopped) return
        stopped = true
        ServiceCompat.stopForeground(
            this,
            if (removeNotification) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH,
        )
        stopSelf()
    }

    private fun post(text: String, ongoing: Boolean) {
        notificationManager().notify(NOTIFICATION_ID, notification(text, ongoing))
    }

    private fun notification(text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        const val CHANNEL_ID = "unison_sync"
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_NAME = "Sync"
        private const val NOTIFICATION_TITLE = "Unison sync"
        private const val EXTRA_PROFILE_ID = "profileId"
        private const val TEXT_RUNNING = "Syncing…"
        private const val TEXT_CONNECTING = "Connecting…"
        private const val TEXT_AWAITING_HOST_KEY = "Waiting for host key approval"

        fun intent(context: Context, profileId: String): Intent =
            Intent(context, SyncService::class.java).putExtra(EXTRA_PROFILE_ID, profileId)

        private fun reasonLabel(reason: SyncState.Reason): String =
            reason.name.lowercase().replace('_', ' ')
    }
}

object ServiceLocator {

    val defaultEngineProvider: (Context) -> SyncEngine = ::buildEngine

    @Volatile
    var engineProvider: (Context) -> SyncEngine = defaultEngineProvider

    val defaultProfilesProvider: (Context) -> ProfileRepository = ::buildProfiles

    @Volatile
    var profilesProvider: (Context) -> ProfileRepository = defaultProfilesProvider

    val defaultKeysProvider: (Context) -> KeyVault = ::buildKeys

    @Volatile
    var keysProvider: (Context) -> KeyVault = defaultKeysProvider

    private var cached: SyncEngine? = null
    private var cachedProvider: ((Context) -> SyncEngine)? = null

    private var cachedProfiles: ProfileRepository? = null
    private var cachedProfilesProvider: ((Context) -> ProfileRepository)? = null

    private var cachedKeys: KeyVault? = null
    private var cachedKeysProvider: ((Context) -> KeyVault)? = null

    private var cachedStore: JsonStore? = null

    @Synchronized
    fun engine(context: Context): SyncEngine {
        val provider = engineProvider
        val existing = cached
        if (existing != null && cachedProvider === provider) return existing
        val created = provider(context)
        cached = created
        cachedProvider = provider
        return created
    }

    @Synchronized
    fun profiles(context: Context): ProfileRepository {
        val provider = profilesProvider
        val existing = cachedProfiles
        if (existing != null && cachedProfilesProvider === provider) return existing
        val created = provider(context)
        cachedProfiles = created
        cachedProfilesProvider = provider
        return created
    }

    @Synchronized
    fun keys(context: Context): KeyVault {
        val provider = keysProvider
        val existing = cachedKeys
        if (existing != null && cachedKeysProvider === provider) return existing
        val created = provider(context)
        cachedKeys = created
        cachedKeysProvider = provider
        return created
    }

    @Synchronized
    fun reset() {
        cached = null
        cachedProvider = null
        cachedProfiles = null
        cachedProfilesProvider = null
        cachedKeys = null
        cachedKeysProvider = null
        cachedStore = null
    }

    @Synchronized
    private fun store(context: Context): JsonStore {
        cachedStore?.let { return it }
        return JsonStore(context.filesDir).also { cachedStore = it }
    }

    private fun buildProfiles(context: Context): ProfileRepository =
        ProfileRepository(store(context))

    private fun buildKeys(context: Context): KeyVault =
        KeyVault(store(context), KeystoreAesCipher())

    private fun buildEngine(context: Context): SyncEngine {
        return SyncEngine(
            binaryLocator = BinaryLocator(File(context.applicationInfo.nativeLibraryDir)),
            profiles = profiles(context),
            keys = keys(context),
            hostKeys = HostKeyStore(store(context)),
            tunnel = SshjTunnel(),
            runnerFactory = { UnisonRunner(it) },
            parserFactory = { OutputParser() },
            unisonDir = File(context.noBackupFilesDir, "unison"),
            clock = Clock.systemUTC(),
        )
    }
}
