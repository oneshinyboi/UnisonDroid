package io.unisondroid.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.unisondroid.app.data.HostKeyStore
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.service.SyncService
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
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RunScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var engine: FakeSyncEngine

    @Before
    fun setUp() {
        ServiceLocator.reset()
        engine = FakeSyncEngine()
        ServiceLocator.engineProvider = { engine }
    }

    @After
    fun tearDown() {
        ServiceLocator.engineProvider = ServiceLocator.defaultEngineProvider
        ServiceLocator.reset()
    }

    @Test
    fun `renders syncing log lines then the finished summary`() {
        compose.setContent { UnisonDroidTheme { RunScreen(profileId = "p1") } }

        engine.states.value = SyncState.Syncing(
            profileId = "p1",
            log = listOf("Looking for changes", "Reconciling changes", "Transferring file1"),
            progress = 0.5f,
        )
        awaitTag(RUN_LOG_TAG)

        compose.onNodeWithText("Looking for changes").assertExists()
        compose.onNodeWithText("Transferring file1").assertExists()
        compose.onNodeWithTag(RUN_PROGRESS_TAG).assertExists()
        compose.onNodeWithText("50%", substring = true).assertExists()

        engine.states.value = SyncState.Finished(
            profileId = "p1",
            summary = SyncSummary(transferred = 3, failed = 1, conflicts = 2),
        )
        awaitTag(RUN_SUMMARY_TAG)

        compose.onNodeWithText("3 transferred", substring = true).assertExists()
        compose.onNodeWithText("1 failed", substring = true).assertExists()
        compose.onNodeWithText("2 conflicts", substring = true).assertExists()
    }

    @Test
    fun `awaiting host key shows fingerprint dialog and wires approve and deny`() {
        compose.setContent { UnisonDroidTheme { RunScreen(profileId = "p1") } }

        engine.states.value = SyncState.AwaitingHostKey("p1", FINGERPRINT)
        awaitText(FINGERPRINT)

        compose.onNodeWithText(FINGERPRINT).assertExists()
        compose.onNodeWithText("Approve").assertExists()
        compose.onNodeWithText("Deny").assertExists()

        compose.onNodeWithText("Approve").performClick()
        compose.onNodeWithText("Deny").performClick()

        assertEquals(listOf(true, false), engine.hostKeyDecisions)
    }

    @Test
    fun `cancel button is visible while syncing and invokes the engine`() {
        compose.setContent { UnisonDroidTheme { RunScreen(profileId = "p1") } }

        engine.states.value = SyncState.Syncing("p1", listOf("working"), 0.1f)
        awaitTag(RUN_CANCEL_TAG)

        compose.onNodeWithTag(RUN_CANCEL_TAG).performClick()
        assertTrue("cancel must be forwarded to the engine", engine.cancelled)
    }

    @Test
    fun `starts the sync service with the profile intent on entry`() {
        compose.setContent { UnisonDroidTheme { RunScreen(profileId = "p42") } }
        compose.waitForIdle()

        val application = RuntimeEnvironment.getApplication()
        val started = shadowOf(application).nextStartedService
        assertNotNull("service must be started on entry", started)
        assertEquals(SyncService::class.java.name, started!!.component?.className)
        assertEquals("p42", started.getStringExtra("profileId"))
    }

    @Test
    fun `failed with binary missing renders an engine-missing card not the raw detail`() {
        compose.setContent { UnisonDroidTheme { RunScreen(profileId = "p1") } }

        engine.states.value = SyncState.Failed(
            profileId = "p1",
            reason = SyncState.Reason.BINARY_MISSING,
            detail = "libunison.so not found in the native library directory",
        )
        awaitTag(RUN_ENGINE_MISSING_TAG)

        compose.onNodeWithText("Unison engine missing", substring = true).assertExists()
        compose.onNodeWithText("libunison.so not found", substring = true).assertExists()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        val FINGERPRINT = "SHA256:" + "A".repeat(43)
    }
}

private class FakeSyncEngine : SyncEngine(
    BinaryLocator(File("/nonexistent/native-lib")),
    ProfileRepository(JsonStore(File("/nonexistent/data"))),
    KeyVault(JsonStore(File("/nonexistent/data")), IdentityCipher),
    HostKeyStore(JsonStore(File("/nonexistent/data"))),
    NoopTunnel,
    { UnisonRunner(it) },
    { OutputParser() },
    File("/nonexistent/unison"),
    Clock.systemUTC(),
) {
    val states = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = states.asStateFlow()

    var cancelled = false
        private set
    val hostKeyDecisions = mutableListOf<Boolean>()

    override suspend fun requestSync(profileId: String): Boolean = true

    override suspend fun respondHostKey(approve: Boolean) {
        hostKeyDecisions += approve
    }

    override fun cancel() {
        cancelled = true
    }
}

private object IdentityCipher : KeyCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain
    override fun decrypt(blob: ByteArray): ByteArray = blob
}

private object NoopTunnel : SshTunnel {
    override suspend fun open(spec: TunnelSpec, decision: HostKeyDecision): TunnelHandle =
        throw UnsupportedOperationException("not used in run screen tests")
}
