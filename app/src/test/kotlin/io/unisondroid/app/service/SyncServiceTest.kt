package io.unisondroid.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Looper
import io.unisondroid.app.data.HostKeyStore
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.HostKeyDecision
import io.unisondroid.app.sync.OutputParser
import io.unisondroid.app.sync.SshTunnel
import io.unisondroid.app.sync.SyncEngine
import io.unisondroid.app.sync.SyncState
import io.unisondroid.app.sync.SyncSummary
import io.unisondroid.app.sync.TunnelHandle
import io.unisondroid.app.sync.TunnelSpec
import io.unisondroid.app.sync.UnisonRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncServiceTest {

    private lateinit var engine: TestSyncEngine
    private var controller: ServiceController<SyncService>? = null

    @Before
    fun setUp() {
        engine = TestSyncEngine()
        ServiceLocator.engineProvider = { engine }
    }

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
        controller = null
        ServiceLocator.engineProvider = ServiceLocator.defaultEngineProvider
    }

    @Test
    fun `startForeground is called with a dataSync-type notification on sync start`() {
        val service = startService()

        assertEquals("prof1", engine.requestedProfileId)
        assertEquals(SyncService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)

        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            service.foregroundServiceType,
        )
        assertTrue(
            "foreground notification must be ongoing while syncing",
            (notification!!.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        )

        val channel = notificationManager().getNotificationChannel(SyncService.CHANNEL_ID)
        assertNotNull("channel unison_sync must be registered", channel)
    }

    @Test
    fun `engine state transitions update the notification text`() {
        startService()

        engine.states.value = SyncState.Connecting("prof1")
        pump()
        assertEquals("Connecting…", notificationText())

        engine.states.value = SyncState.Syncing("prof1", listOf("log line"), 0.5f)
        pump()
        val syncing = notificationText()
        assertNotNull(syncing)
        assertTrue(syncing!!.contains("Syncing"))
        assertTrue(syncing.contains("50%"))

        engine.states.value = SyncState.AwaitingHostKey("prof1", FINGERPRINT)
        pump()
        assertEquals("Waiting for host key approval", notificationText())

        engine.states.value = SyncState.Finished("prof1", SyncSummary(transferred = 3, failed = 1, conflicts = 2))
        pump()
        val finished = notificationText()
        assertNotNull(finished)
        assertTrue(finished!!.contains("Sync finished"))
        assertTrue(finished.contains("3 transferred"))
        assertTrue(finished.contains("1 failed"))
        assertTrue(finished.contains("2 conflicts"))
        assertTrue(
            "finished notification must not be ongoing",
            (notification()!!.flags and Notification.FLAG_ONGOING_EVENT) == 0,
        )
    }

    @Test
    fun `stopSelf is called when engine reaches Finished`() {
        val service = startService()

        engine.states.value = SyncState.Connecting("prof1")
        pump()
        assertTrue(!shadowOf(service).isStoppedBySelf)

        engine.states.value = SyncState.Finished("prof1", SyncSummary(transferred = 1, failed = 0, conflicts = 0))
        pump()

        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `stopSelf is called when engine reaches Failed`() {
        val service = startService()

        engine.states.value = SyncState.Syncing("prof1", emptyList(), 0.25f)
        pump()
        engine.states.value = SyncState.Failed("prof1", SyncState.Reason.AUTH, "bad key")
        pump()

        assertTrue(shadowOf(service).isStoppedBySelf)
        val failed = notificationText()
        assertNotNull(failed)
        assertTrue(failed!!.contains("Sync failed"))
        assertTrue(failed.contains("auth"))
    }

    @Test
    fun `stopSelf is called when engine returns to Idle after activity`() {
        val service = startService()

        engine.states.value = SyncState.Idle
        pump()
        assertTrue("initial Idle must not stop the service", !shadowOf(service).isStoppedBySelf)

        engine.states.value = SyncState.Connecting("prof1")
        pump()
        engine.states.value = SyncState.Idle
        pump()

        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    private fun startService(): SyncService {
        val context = RuntimeEnvironment.getApplication() as Context
        controller = Robolectric.buildService(SyncService::class.java, SyncService.intent(context, "prof1"))
        controller!!.create().startCommand(0, 0)
        return controller!!.get()
    }

    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun notificationManager(): NotificationManager =
        RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)

    private fun notification(): Notification? = shadowOf(notificationManager()).getNotification(SyncService.NOTIFICATION_ID)

    private fun notificationText(): String? =
        notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private companion object {
        val FINGERPRINT = "SHA256:" + "A".repeat(43)
    }
}

private class TestSyncEngine : SyncEngine(
    BinaryLocator(File("/nonexistent/native-lib")),
    ProfileRepository(JsonStore(File("/nonexistent/data"))),
    KeyVault(JsonStore(File("/nonexistent/data")), IdentityCipher),
    HostKeyStore(JsonStore(File("/nonexistent/data"))),
    ThrowingTunnel,
    { UnisonRunner(it) },
    { OutputParser() },
    File("/nonexistent/unison"),
    Clock.systemUTC(),
) {
    val states = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = states.asStateFlow()

    var requestedProfileId: String? = null

    override suspend fun requestSync(profileId: String): Boolean {
        requestedProfileId = profileId
        return true
    }
}

private object IdentityCipher : KeyCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain
    override fun decrypt(blob: ByteArray): ByteArray = blob
}

private object ThrowingTunnel : SshTunnel {
    override suspend fun open(spec: TunnelSpec, decision: HostKeyDecision): TunnelHandle =
        throw UnsupportedOperationException("not used in service tests")
}
