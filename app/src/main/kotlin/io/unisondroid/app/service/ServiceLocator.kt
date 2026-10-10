package io.unisondroid.app.service

import android.content.Context
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.SettingsRepository
import io.unisondroid.app.data.TinkKeyCipher
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.OutputParser
import io.unisondroid.app.sync.ProcessSshTool
import io.unisondroid.app.sync.SyncEngine
import io.unisondroid.app.sync.UnisonRunner
import java.io.File
import java.time.Clock

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

    val defaultSettingsProvider: (Context) -> SettingsRepository = ::buildSettings

    @Volatile
    var settingsProvider: (Context) -> SettingsRepository = defaultSettingsProvider

    private var cached: SyncEngine? = null
    private var cachedProvider: ((Context) -> SyncEngine)? = null

    private var cachedProfiles: ProfileRepository? = null
    private var cachedProfilesProvider: ((Context) -> ProfileRepository)? = null

    private var cachedKeys: KeyVault? = null
    private var cachedKeysProvider: ((Context) -> KeyVault)? = null

    private var cachedSettings: SettingsRepository? = null
    private var cachedSettingsProvider: ((Context) -> SettingsRepository)? = null

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
    fun settings(context: Context): SettingsRepository {
        val provider = settingsProvider
        val existing = cachedSettings
        if (existing != null && cachedSettingsProvider === provider) return existing
        val created = provider(context)
        cachedSettings = created
        cachedSettingsProvider = provider
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
        cachedSettings = null
        cachedSettingsProvider = null
        cachedStore = null
    }

    @Synchronized
    private fun store(context: Context): JsonStore {
        cachedStore?.let { return it }
        return JsonStore(context.filesDir).also { cachedStore = it }
    }

    private fun buildProfiles(context: Context): ProfileRepository =
        ProfileRepository(store(context))

    private fun buildSettings(context: Context): SettingsRepository =
        SettingsRepository(store(context))

    private fun buildKeys(context: Context): KeyVault =
        KeyVault(store(context), TinkKeyCipher(context), sshTool(context))

    private fun sshTool(context: Context): ProcessSshTool {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        return ProcessSshTool(
            keygen = File(nativeDir, "libssh-keygen.so"),
            keyscan = File(nativeDir, "libssh-keyscan.so"),
            workDir = File(context.cacheDir, "ssh"),
        )
    }

    private fun buildEngine(context: Context): SyncEngine {
        return SyncEngine(
            binaryLocator = BinaryLocator(File(context.applicationInfo.nativeLibraryDir)),
            profiles = profiles(context),
            keys = keys(context),
            sshTool = sshTool(context),
            runnerFactory = { UnisonRunner(it) },
            parserFactory = { OutputParser() },
            unisonDir = File(context.noBackupFilesDir, "unison"),
            sshHome = File(context.noBackupFilesDir, "ssh"),
            clock = Clock.systemUTC(),
        )
    }
}
