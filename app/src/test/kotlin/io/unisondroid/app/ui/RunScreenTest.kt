package io.unisondroid.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.unisondroid.app.data.ConflictRecord
import io.unisondroid.app.data.FailedRecord
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.FakeSshTool
import io.unisondroid.app.sync.OutputParser
import io.unisondroid.app.sync.SyncEngine
import io.unisondroid.app.sync.SyncMode
import io.unisondroid.app.sync.SyncOutcome
import io.unisondroid.app.sync.SyncState
import io.unisondroid.app.sync.SyncSummary
import io.unisondroid.app.sync.SyncVariant
import io.unisondroid.app.sync.UnisonRunner
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
        ServiceLocator.reset()
        engine = FakeSyncEngine()
        ServiceLocator.engineProvider = { engine }
    }

    @After
    fun tearDown() {
        runCatching { WorkManager.getInstance(RuntimeEnvironment.getApplication()).cancelAllWork() }
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
            summary = SyncSummary(
                transferred = 3,
                conflicts = listOf(ConflictRecord("a.txt", "conflicting updates")),
                failed = listOf(FailedRecord("b.pdf", "boom")),
            ),
        )
        awaitTag(RUN_SUMMARY_TAG)

        compose.onNodeWithText("3 transferred", substring = true).assertExists()
        compose.onNodeWithTag(RUN_CONFLICT_ROW).assertExists()
        compose.onNodeWithText("a.txt", substring = true).assertExists()
        compose.onNodeWithText("conflicting updates", substring = true).assertExists()
        compose.onNodeWithTag(RUN_FAILED_ROW).assertExists()
        compose.onNodeWithText("b.pdf", substring = true).assertExists()
        compose.onNodeWithText("boom", substring = true).assertExists()
    }

    @Test
    fun `finished summary with a resolvable conflict shows the resolve button and invokes it`() {
        var resolved = false
        compose.setContent {
            UnisonDroidTheme {
                RunScreen(profileId = "p1", onResolveConflicts = { resolved = true })
            }
        }

        engine.states.value = SyncState.Finished(
            profileId = "p1",
            summary = SyncSummary(
                transferred = 1,
                conflicts = listOf(
                    ConflictRecord("a.txt", "conflicting updates", resolvable = true),
                ),
                failed = emptyList(),
            ),
        )
        awaitTag(RUN_RESOLVE_TAG)

        compose.onNodeWithTag(RUN_RESOLVE_TAG).assertExists()
        compose.onNodeWithTag(RUN_RESOLVE_TAG).performClick()
        assertTrue("the resolve button must be forwarded to the caller", resolved)
    }

    @Test
    fun `finished summary without resolvable conflicts hides the resolve button`() {
        compose.setContent { UnisonDroidTheme { RunScreen(profileId = "p1") } }

        engine.states.value = SyncState.Finished(
            profileId = "p1",
            summary = SyncSummary(
                transferred = 1,
                conflicts = listOf(ConflictRecord("a.txt", "atomic directory", resolvable = false)),
                failed = emptyList(),
            ),
        )
        awaitTag(RUN_SUMMARY_TAG)

        compose.onNodeWithTag(RUN_RESOLVE_TAG).assertDoesNotExist()
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
    fun `enqueues the one-shot unique sync on entry`() {
        compose.setContent { UnisonDroidTheme { RunScreen(profileId = "p42") } }
        compose.waitForIdle()

        val context = RuntimeEnvironment.getApplication()
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("sync-p42-now")
            .get()
        assertTrue("RunScreen must enqueue the one-shot sync", infos.isNotEmpty())
        assertEquals(1, infos.size)
        assertNull("manual sync must be one-shot", infos.first().periodicityInfo)
    }

    @Test
    fun `a diagnostic run says testing connection instead of syncing`() {
        compose.setContent {
            UnisonDroidTheme { RunScreen(profileId = "p1", variant = SyncVariant.TEST_CONNECTION) }
        }

        compose.onNodeWithText("Testing connection", substring = true).assertExists()
    }

    @Test
    fun `a syncing diagnostic shows the testing-connection progress label`() {
        compose.setContent {
            UnisonDroidTheme { RunScreen(profileId = "p1", variant = SyncVariant.TEST_CONNECTION) }
        }

        engine.states.value = SyncState.Syncing("p1", listOf("working"), 0.5f)
        awaitTag(RUN_PROGRESS_TAG)

        compose.onNodeWithText("Testing connection", substring = true).assertExists()
    }

    @Test
    fun `passes destructive confirmation to the sync run`() {
        compose.setContent {
            UnisonDroidTheme {
                RunScreen(profileId = "p42", variant = SyncVariant.MIRROR_TO_SERVER, confirmed = true)
            }
        }

        compose.waitUntil(5_000) { engine.requestedVariant == SyncVariant.MIRROR_TO_SERVER }
        assertTrue(engine.requestedConfirmed)
    }

    @Test
    fun `shows the run variant as the title`() {
        compose.setContent {
            UnisonDroidTheme { RunScreen(profileId = "p1", variant = SyncVariant.COPY_FROM_SERVER) }
        }

        compose.onNodeWithText("Copy from server").assertExists()
    }

    @Test
    fun `passes the selected variant to the sync run`() {
        compose.setContent {
            UnisonDroidTheme { RunScreen(profileId = "p42", variant = SyncVariant.MIRROR_TO_SERVER) }
        }

        compose.waitUntil(5_000) { engine.requestedVariant == SyncVariant.MIRROR_TO_SERVER }
        assertEquals(SyncVariant.MIRROR_TO_SERVER, engine.requestedVariant)
    }

    @Test
    fun `a finished connection test shows a connection card not a sync summary`() {
        compose.setContent {
            UnisonDroidTheme { RunScreen(profileId = "p1", variant = SyncVariant.TEST_CONNECTION) }
        }

        engine.states.value = SyncState.Finished(
            profileId = "p1",
            summary = SyncSummary(transferred = 0, conflicts = emptyList(), failed = emptyList()),
        )
        awaitTag(RUN_CONNECTION_OK_TAG)

        compose.onNodeWithText("Connection OK", substring = true).assertExists()
        compose.onNodeWithText("transferred", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a stale terminal state is not shown before the new run starts`() {
        engine.states.value = SyncState.Finished(
            profileId = "p1",
            summary = SyncSummary(transferred = 9, conflicts = emptyList(), failed = emptyList()),
        )

        compose.setContent {
            UnisonDroidTheme { RunScreen(profileId = "p1", variant = SyncVariant.TEST_CONNECTION) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(RUN_CONNECTION_OK_TAG).assertDoesNotExist()
        compose.onNodeWithText("9 transferred", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a failed connection test uses a connection-specific failure title`() {
        compose.setContent {
            UnisonDroidTheme { RunScreen(profileId = "p1", variant = SyncVariant.TEST_CONNECTION) }
        }

        engine.states.value = SyncState.Failed(
            profileId = "p1",
            reason = SyncState.Reason.AUTH,
            detail = "Permission denied",
        )
        awaitTag(RUN_FAILURE_TAG)

        compose.onNodeWithText("Connection test failed").assertExists()
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

    @Test
    fun `local permissions failure surfaces the all files access grant action`() {
        compose.setContent { UnisonDroidTheme { RunScreen(profileId = "p1") } }

        engine.states.value = SyncState.Failed(
            profileId = "p1",
            reason = SyncState.Reason.LOCAL_PERMISSIONS,
            detail = "Permission denied",
        )
        awaitTag(GRANT_ACCESS_TAG)

        compose.onNodeWithTag(GRANT_ACCESS_TAG).assertExists()
        compose.onNodeWithText("Grant All Files Access").assertExists()
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
    KeyVault(JsonStore(File("/nonexistent/data")), IdentityCipher, FakeSshTool()),
    FakeSshTool(),
    { UnisonRunner(it) },
    { OutputParser() },
    File("/nonexistent/unison"),
    File("/nonexistent/ssh"),
    Clock.systemUTC(),
) {
    /** The engine's own state flow, exposed so tests can drive and observe it. */
    val states: MutableStateFlow<SyncState> get() = _state

    var cancelled = false
        private set
    val hostKeyDecisions = mutableListOf<Boolean>()

    @Volatile
    var requestedVariant: SyncVariant? = null
        private set

    @Volatile
    var requestedConfirmed: Boolean = false
        private set

    override suspend fun requestSync(
        profileId: String,
        mode: SyncMode,
        variant: SyncVariant,
        confirmed: Boolean,
    ): SyncOutcome {
        requestedVariant = variant
        requestedConfirmed = confirmed
        return SyncOutcome.COMPLETED
    }

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
