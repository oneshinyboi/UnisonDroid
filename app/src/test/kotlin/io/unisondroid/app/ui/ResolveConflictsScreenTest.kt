package io.unisondroid.app.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.work.testing.WorkManagerTestInitHelper
import io.unisondroid.app.data.ConflictRecord
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.SideInfo
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.FakeSshTool
import io.unisondroid.app.sync.OutputParser
import io.unisondroid.app.sync.Resolution
import io.unisondroid.app.sync.SyncEngine
import io.unisondroid.app.sync.SyncOutcome
import io.unisondroid.app.sync.SyncState
import io.unisondroid.app.sync.UnisonRunner
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResolveConflictsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var repository: ProfileRepository
    private lateinit var engine: RecordingEngine

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
        val dir = Files.createTempDirectory("resolve-conflicts-test").toFile()
        repository = ProfileRepository(JsonStore(dir))
        engine = RecordingEngine()
        ServiceLocator.reset()
        ServiceLocator.profilesProvider = { repository }
        ServiceLocator.engineProvider = { engine }
    }

    @After
    fun tearDown() {
        ServiceLocator.profilesProvider = ServiceLocator.defaultProfilesProvider
        ServiceLocator.engineProvider = ServiceLocator.defaultEngineProvider
        ServiceLocator.reset()
    }

    @Test
    fun `renders conflicts, records keep-local decision, and disables non-resolvable rows`() {
        val resolvable = ConflictRecord(
            path = "notes/plan.txt",
            reason = "conflicting updates",
            local = SideInfo(sizeBytes = 120, modifiedAt = 1_700_000_000_000L, kind = "file"),
            remote = SideInfo(sizeBytes = 90, modifiedAt = 1_700_000_100_000L, kind = "file"),
            resolvable = true,
        )
        val blocked = ConflictRecord(
            path = "photos/pic.jpg",
            reason = "atomic directory",
            resolvable = false,
        )
        runBlocking { repository.save(seedProfile(lastConflicts = listOf(resolvable, blocked))) }

        compose.setContent {
            UnisonDroidTheme { ResolveConflictsScreen(profileId = "p1") }
        }

        awaitTag("$RESOLVE_ROW_PREFIX${resolvable.path}")
        awaitTag("$RESOLVE_ROW_PREFIX${blocked.path}")

        compose.onNodeWithText("notes/plan.txt", substring = true).assertExists()
        compose.onNodeWithText("conflicting updates", substring = true).assertExists()
        compose.onNodeWithText("photos/pic.jpg", substring = true).assertExists()
        compose.onNodeWithText("atomic directory", substring = true).assertExists()
        compose.onNodeWithText("cannot resolve automatically", substring = true).assertExists()

        compose.onNodeWithTag("$RESOLVE_KEEP_LOCAL-${resolvable.path}").performClick()
        compose.onNodeWithTag(RESOLVE_SYNC_TAG).performScrollTo().performClick()

        compose.waitUntil(5_000) { engine.recorded != null }
        assertEquals(
            mapOf(resolvable.path to Resolution.KEEP_LOCAL),
            engine.recorded,
        )

        compose.onNodeWithTag("$RESOLVE_KEEP_LOCAL-${blocked.path}").assertDoesNotExist()
        compose.onNodeWithTag("$RESOLVE_KEEP_REMOTE-${blocked.path}").assertDoesNotExist()
        compose.onNodeWithTag("$RESOLVE_KEEP_BOTH-${blocked.path}").assertDoesNotExist()
        compose.onNodeWithTag("$RESOLVE_SKIP-${blocked.path}").assertDoesNotExist()
    }

    @Test
    fun `profile without conflicts shows the empty state`() {
        runBlocking { repository.save(seedProfile(lastConflicts = emptyList())) }

        compose.setContent {
            UnisonDroidTheme { ResolveConflictsScreen(profileId = "p1") }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No conflicts to resolve", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("No conflicts to resolve", substring = true).assertExists()
        compose.onNodeWithTag(RESOLVE_SYNC_TAG).assertDoesNotExist()
        assertNull(engine.recorded)
    }

    @Test
    fun `busy resolve outcome shows the busy message`() {
        val resolvable = ConflictRecord("notes/plan.txt", "conflicting updates", resolvable = true)
        runBlocking { repository.save(seedProfile(lastConflicts = listOf(resolvable))) }
        engine.nextOutcome = SyncOutcome.BUSY

        compose.setContent {
            UnisonDroidTheme { ResolveConflictsScreen(profileId = "p1") }
        }
        awaitTag("$RESOLVE_ROW_PREFIX${resolvable.path}")

        compose.onNodeWithTag("$RESOLVE_KEEP_LOCAL-${resolvable.path}").performClick()
        compose.onNodeWithTag(RESOLVE_SYNC_TAG).performScrollTo().performClick()
        compose.waitUntil(5_000) { engine.recorded != null }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(RESOLVE_BUSY_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("A sync is already running", substring = true).assertExists()
    }

    @Test
    fun `failed resolve outcome surfaces the failure`() {
        val resolvable = ConflictRecord("notes/plan.txt", "conflicting updates", resolvable = true)
        runBlocking { repository.save(seedProfile(lastConflicts = listOf(resolvable))) }
        engine.nextOutcome = SyncOutcome.FAILED
        engine.failureDetail = "process exited 3"

        compose.setContent {
            UnisonDroidTheme { ResolveConflictsScreen(profileId = "p1") }
        }
        awaitTag("$RESOLVE_ROW_PREFIX${resolvable.path}")

        compose.onNodeWithTag("$RESOLVE_KEEP_LOCAL-${resolvable.path}").performClick()
        compose.onNodeWithTag(RESOLVE_SYNC_TAG).performScrollTo().performClick()
        compose.waitUntil(5_000) { engine.recorded != null }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(RESOLVE_FAILURE_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("process exited 3", substring = true).assertExists()
    }

    @Test
    fun `sync button stays disabled until a decision is chosen`() {
        val resolvable = ConflictRecord("notes/plan.txt", "conflicting updates", resolvable = true)
        runBlocking { repository.save(seedProfile(lastConflicts = listOf(resolvable))) }

        compose.setContent {
            UnisonDroidTheme { ResolveConflictsScreen(profileId = "p1") }
        }
        awaitTag("$RESOLVE_ROW_PREFIX${resolvable.path}")

        compose.onNodeWithTag(RESOLVE_SYNC_TAG).performScrollTo().assertIsNotEnabled()
        assertNull(engine.recorded)

        compose.onNodeWithTag("$RESOLVE_KEEP_LOCAL-${resolvable.path}").performClick()
        compose.onNodeWithTag(RESOLVE_SYNC_TAG).assertIsEnabled()
    }

    @Test
    fun `unknown profile shows a not-found state`() {
        compose.setContent {
            UnisonDroidTheme { ResolveConflictsScreen(profileId = "missing") }
        }

        awaitTag(RESOLVE_MISSING_TAG)
        compose.onNodeWithText("Profile not found", substring = true).assertExists()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun seedProfile(lastConflicts: List<ConflictRecord>): Profile = Profile(
        id = "p1",
        name = "Phone",
        localRoot = "/storage/emulated/0/Unison",
        remoteRoot = "/home/user/sync",
        host = "server.example.com",
        user = "user",
        sshKeyId = "key1",
        lastConflicts = lastConflicts,
    )
}

private class RecordingEngine : SyncEngine(
    BinaryLocator(File("/nonexistent/native-lib")),
    ProfileRepository(JsonStore(File("/nonexistent/data"))),
    KeyVault(JsonStore(File("/nonexistent/data")), ResolveIdentityCipher, FakeSshTool()),
    FakeSshTool(),
    { UnisonRunner(it) },
    { OutputParser() },
    File("/nonexistent/unison"),
    File("/nonexistent/ssh"),
    Clock.systemUTC(),
) {
    val states = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = states.asStateFlow()

    var recorded: Map<String, Resolution>? = null
        private set

    var nextOutcome: SyncOutcome = SyncOutcome.COMPLETED
    var failureDetail: String? = null

    override suspend fun resolveConflicts(
        profileId: String,
        decisions: Map<String, Resolution>,
    ): SyncOutcome {
        recorded = decisions
        failureDetail?.let { states.value = SyncState.Failed(profileId, SyncState.Reason.EXIT, it) }
        return nextOutcome
    }
}

private object ResolveIdentityCipher : KeyCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain
    override fun decrypt(blob: ByteArray): ByteArray = blob
}
