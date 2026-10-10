package io.unisondroid.app.service

import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.FakeSshTool
import io.unisondroid.app.sync.OutputParser
import io.unisondroid.app.sync.SyncEngine
import io.unisondroid.app.sync.SyncMode
import io.unisondroid.app.sync.SyncOutcome
import io.unisondroid.app.sync.SyncState
import io.unisondroid.app.sync.UnisonRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.time.Clock

/**
 * A [SyncEngine] stub for service-layer tests: records the requested profile/mode and returns a
 * scripted [SyncOutcome] without touching the filesystem or spawning processes.
 */
class StubSyncEngine : SyncEngine(
    BinaryLocator(File("/nonexistent/native-lib")),
    ProfileRepository(JsonStore(File("/nonexistent/data"))),
    KeyVault(JsonStore(File("/nonexistent/data")), IdentityCipher, FakeSshTool()),
    FakeSshTool(),
    { UnisonRunner(it) },
    { OutputParser() },
    File("/nonexistent/unison"),
    File("/nonexistent/ssh"),
    Clock.systemUTC(),
) {
    val states = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = states.asStateFlow()

    var outcome: SyncOutcome = SyncOutcome.COMPLETED
    var throwOnRequest: Throwable? = null

    /** Counts down when [requestSync] is entered, so tests can await a run without polling. */
    val requestLatch = CountDownLatch(1)

    @Volatile
    var requestedProfileId: String? = null
        private set

    @Volatile
    var requestedMode: SyncMode? = null
        private set

    @Volatile
    var requestCount = 0
        private set

    override suspend fun requestSync(profileId: String, mode: SyncMode): SyncOutcome {
        requestedProfileId = profileId
        requestedMode = mode
        requestCount += 1
        requestLatch.countDown()
        throwOnRequest?.let { throw it }
        return outcome
    }
}

private object IdentityCipher : KeyCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain
    override fun decrypt(blob: ByteArray): ByteArray = blob
}
