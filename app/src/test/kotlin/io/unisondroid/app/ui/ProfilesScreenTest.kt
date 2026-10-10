package io.unisondroid.app.ui

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.unisondroid.app.data.AppSettings
import io.unisondroid.app.data.ConflictRecord
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.SyncResult
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.service.StubSyncEngine
import io.unisondroid.app.service.SyncScheduler
import io.unisondroid.app.sync.SyncState
import io.unisondroid.app.sync.SyncVariant
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var repository: ProfileRepository
    private lateinit var engine: StubSyncEngine

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
        val dir = Files.createTempDirectory("profiles-screen-test").toFile()
        repository = ProfileRepository(JsonStore(dir))
        ServiceLocator.reset()
        ServiceLocator.profilesProvider = { repository }
        engine = StubSyncEngine()
        ServiceLocator.engineProvider = { engine }
    }

    @After
    fun tearDown() {
        ServiceLocator.profilesProvider = ServiceLocator.defaultProfilesProvider
        ServiceLocator.engineProvider = ServiceLocator.defaultEngineProvider
        ServiceLocator.reset()
    }

    @Test
    fun `empty repository renders the getting-started card`() {
        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(onOpenProfile = {}, onStartSync = {})
            }
        }

        compose.onNodeWithText("Server setup").assertExists()
        compose.onNodeWithText(GETTING_STARTED_HINT, substring = true).assertExists()
    }

    @Test
    fun `seeded profile renders name status chip and last-sync time`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(onOpenProfile = {}, onStartSync = {})
            }
        }

        compose.onNodeWithText("My Server").assertExists()
        compose.onNodeWithText("OK").assertExists()
        compose.onNodeWithText(expectedLastSyncLabel(1_700_000_000_000L)).assertExists()
    }

    @Test
    fun `last sync label uses the local time zone`() {
        val at = 1_700_000_000_000L

        assertEquals(expectedLastSyncLabel(at), lastSyncLabel(at))
        assertEquals("Never synced", lastSyncLabel(null))
    }

    @Test
    fun `tapping a profile calls onStartSync with its id`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        var started: String? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(onOpenProfile = {}, onStartSync = { started = it })
            }
        }

        compose.onNodeWithText("My Server").performClick()

        assertEquals("p1", started)
    }

    @Test
    fun `edit action opens the editor for that profile`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        var opened: String? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(onOpenProfile = { opened = it }, onStartSync = {})
            }
        }

        awaitTag("${PROFILE_EDIT_PREFIX}p1")
        compose.onNodeWithTag("${PROFILE_EDIT_PREFIX}p1").performClick()

        assertEquals("p1", opened)
    }

    @Test
    fun `delete action confirms then removes the profile from the list`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(onOpenProfile = {}, onStartSync = {})
            }
        }

        awaitTag("${PROFILE_DELETE_PREFIX}p1")
        compose.onNodeWithTag("${PROFILE_DELETE_PREFIX}p1").performClick()
        awaitTag(DELETE_CONFIRM_TAG)
        compose.onNodeWithTag(DELETE_CONFIRM_TAG).performClick()

        compose.waitUntil(5_000) { runBlocking { repository.profiles() }.isEmpty() }
        compose.onNodeWithText("My Server").assertDoesNotExist()
    }

    @Test
    fun `deleting an auto-sync profile cancels its scheduled work`() {
        val context = RuntimeEnvironment.getApplication()
        runBlocking {
            repository.save(profile(id = "p1", name = "My Server").copy(autoSyncEnabled = true))
            SyncScheduler(context).reconcile(repository.profiles(), AppSettings())
        }
        assertEquals(
            WorkInfo.State.ENQUEUED,
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.periodicWorkName("p1"))
                .get()
                .single()
                .state,
        )

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(onOpenProfile = {}, onStartSync = {})
            }
        }

        awaitTag("${PROFILE_DELETE_PREFIX}p1")
        compose.onNodeWithTag("${PROFILE_DELETE_PREFIX}p1").performClick()
        awaitTag(DELETE_CONFIRM_TAG)
        compose.onNodeWithTag(DELETE_CONFIRM_TAG).performClick()

        compose.waitUntil(5_000) {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.periodicWorkName("p1"))
                .get()
                .singleOrNull()
                ?.state == WorkInfo.State.CANCELLED
        }
    }

    @Test
    fun `profile row counts only resolvable conflicts`() {
        runBlocking {
            repository.save(
                profile(id = "p1", name = "My Server").copy(
                    lastConflicts = listOf(
                        ConflictRecord("a.txt", "conflicting updates", resolvable = true),
                        ConflictRecord("b.txt", "contents changed on both sides", resolvable = true),
                        ConflictRecord("broken/", "Syncing symbolic links is disabled", resolvable = false),
                    ),
                ),
            )
        }

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(onOpenProfile = {}, onStartSync = {})
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("2 conflicts", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("2 conflicts", substring = true).assertExists()
        compose.onNodeWithText("3 conflicts", substring = true).assertDoesNotExist()
    }

    @Test
    fun `resolve action on a conflicted profile invokes onResolveConflicts with its id`() {
        var resolved: String? = null
        val conflicting = profile(id = "p1", name = "My Server").copy(
            lastConflicts = listOf(
                ConflictRecord("a.txt", "conflicting updates", resolvable = true),
            ),
        )

        compose.setContent {
            UnisonDroidTheme {
                ProfilesContent(
                    profiles = listOf(conflicting),
                    onOpenProfile = {},
                    onStartSync = {},
                    onResolveConflicts = { resolved = it },
                )
            }
        }

        awaitTag("${PROFILE_RESOLVE_PREFIX}p1")
        compose.onNodeWithTag("${PROFILE_RESOLVE_PREFIX}p1").performClick()

        assertEquals("p1", resolved)
    }

    @Test
    fun `resolve action is hidden without resolvable conflicts`() {
        val noConflicts = profile(id = "p1", name = "No Conflicts")
        val unresolvable = profile(id = "p2", name = "Unresolvable").copy(
            lastConflicts = listOf(
                ConflictRecord("a.txt", "atomic directory", resolvable = false),
            ),
        )

        compose.setContent {
            UnisonDroidTheme {
                ProfilesContent(
                    profiles = listOf(noConflicts, unresolvable),
                    onOpenProfile = {},
                    onStartSync = {},
                    onResolveConflicts = {},
                )
            }
        }

        compose.onNodeWithText("No Conflicts").assertExists()
        compose.onNodeWithText("Unresolvable").assertExists()
        compose.onNodeWithTag("${PROFILE_RESOLVE_PREFIX}p1").assertDoesNotExist()
        compose.onNodeWithTag("${PROFILE_RESOLVE_PREFIX}p2").assertDoesNotExist()
    }

    @Test
    fun `getting-started card offers the all files access grant when access is missing`() {
        var granted = false

        compose.setContent {
            UnisonDroidTheme {
                ProfilesContent(
                    profiles = emptyList(),
                    onOpenProfile = {},
                    onStartSync = {},
                    hasLocalAccess = false,
                    onRequestAccess = { granted = true },
                )
            }
        }

        compose.onNodeWithTag(GRANT_ACCESS_TAG).performScrollTo().performClick()

        assertTrue(granted)
    }

    @Test
    fun `long pressing a profile opens the run menu`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }

        compose.setContent {
            UnisonDroidTheme { ProfilesScreen(onOpenProfile = {}, onStartSync = {}) }
        }

        awaitTag("$PROFILE_ROW_TAG-p1")
        compose.onNodeWithTag("$PROFILE_ROW_TAG-p1").performTouchInput { longClick() }

        awaitTag(actionTag("p1", SyncVariant.TEST_CONNECTION))
        compose.onNodeWithTag(actionTag("p1", SyncVariant.TEST_CONNECTION)).assertExists()
    }

    @Test
    fun `copy to server runs the chosen variant`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        var ran: Triple<String, SyncVariant, Boolean>? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(
                    onOpenProfile = {},
                    onStartSync = {},
                    onRunVariant = { id, variant, confirmed -> ran = Triple(id, variant, confirmed) },
                )
            }
        }

        awaitTag("$PROFILE_ROW_TAG-p1")
        compose.onNodeWithTag("$PROFILE_ROW_TAG-p1").performTouchInput { longClick() }
        awaitTag(actionTag("p1", SyncVariant.COPY_TO_SERVER))
        compose.onNodeWithTag(actionTag("p1", SyncVariant.COPY_TO_SERVER)).performClick()

        assertEquals(Triple("p1", SyncVariant.COPY_TO_SERVER, false), ran)
    }

    @Test
    fun `rebuild archives runs the chosen variant without confirmation`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        var ran: Triple<String, SyncVariant, Boolean>? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(
                    onOpenProfile = {},
                    onStartSync = {},
                    onRunVariant = { id, variant, confirmed -> ran = Triple(id, variant, confirmed) },
                )
            }
        }

        awaitTag("$PROFILE_ROW_TAG-p1")
        compose.onNodeWithTag("$PROFILE_ROW_TAG-p1").performTouchInput { longClick() }
        awaitTag(actionTag("p1", SyncVariant.REBUILD_ARCHIVES))
        compose.onNodeWithTag(actionTag("p1", SyncVariant.REBUILD_ARCHIVES)).performClick()

        assertEquals(Triple("p1", SyncVariant.REBUILD_ARCHIVES, false), ran)
    }

    @Test
    fun `mirror to server confirms before running`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        var ran: Triple<String, SyncVariant, Boolean>? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(
                    onOpenProfile = {},
                    onStartSync = {},
                    onRunVariant = { id, variant, confirmed -> ran = Triple(id, variant, confirmed) },
                )
            }
        }

        awaitTag("$PROFILE_ROW_TAG-p1")
        compose.onNodeWithTag("$PROFILE_ROW_TAG-p1").performTouchInput { longClick() }
        awaitTag(actionTag("p1", SyncVariant.MIRROR_TO_SERVER))
        compose.onNodeWithTag(actionTag("p1", SyncVariant.MIRROR_TO_SERVER)).performClick()

        awaitTag(MIRROR_CONFIRM_TAG)
        assertEquals("mirroring must not run before confirmation", null, ran)

        compose.onNodeWithTag(MIRROR_CONFIRM_TAG).performClick()

        assertEquals(Triple("p1", SyncVariant.MIRROR_TO_SERVER, true), ran)
    }

    @Test
    fun `mirror from server confirms before running`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        var ran: Triple<String, SyncVariant, Boolean>? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(
                    onOpenProfile = {},
                    onStartSync = {},
                    onRunVariant = { id, variant, confirmed -> ran = Triple(id, variant, confirmed) },
                )
            }
        }

        awaitTag("$PROFILE_ROW_TAG-p1")
        compose.onNodeWithTag("$PROFILE_ROW_TAG-p1").performTouchInput { longClick() }
        awaitTag(actionTag("p1", SyncVariant.MIRROR_FROM_SERVER))
        compose.onNodeWithTag(actionTag("p1", SyncVariant.MIRROR_FROM_SERVER)).performClick()

        awaitTag(MIRROR_CONFIRM_TAG)
        assertEquals("mirroring must not run before confirmation", null, ran)

        compose.onNodeWithTag(MIRROR_CONFIRM_TAG).performClick()

        assertEquals(Triple("p1", SyncVariant.MIRROR_FROM_SERVER, true), ran)
    }

    @Test
    fun `canceling a mirror confirmation runs nothing`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        var ran: Triple<String, SyncVariant, Boolean>? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(
                    onOpenProfile = {},
                    onStartSync = {},
                    onRunVariant = { id, variant, confirmed -> ran = Triple(id, variant, confirmed) },
                )
            }
        }

        awaitTag("$PROFILE_ROW_TAG-p1")
        compose.onNodeWithTag("$PROFILE_ROW_TAG-p1").performTouchInput { longClick() }
        awaitTag(actionTag("p1", SyncVariant.MIRROR_TO_SERVER))
        compose.onNodeWithTag(actionTag("p1", SyncVariant.MIRROR_TO_SERVER)).performClick()
        awaitTag(MIRROR_CONFIRM_TAG)

        compose.onNodeWithText("Cancel").performClick()

        assertEquals("cancelling must not run anything", null, ran)
    }

    @Test
    fun `tapping a profile is blocked with a message while a sync is active`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        engine.states.value = SyncState.Syncing("p1", listOf("working"), 0.1f)
        var started: String? = null

        compose.setContent {
            UnisonDroidTheme { ProfilesScreen(onOpenProfile = {}, onStartSync = { started = it }) }
        }

        compose.onNodeWithText("My Server").performClick()

        awaitText(SYNC_BUSY_MESSAGE)
        assertEquals(null, started)
    }

    @Test
    fun `a run variant is blocked with a message while a sync is active`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        engine.states.value = SyncState.Syncing("p1", listOf("working"), 0.1f)
        var ran: Triple<String, SyncVariant, Boolean>? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(
                    onOpenProfile = {},
                    onStartSync = {},
                    onRunVariant = { id, variant, confirmed -> ran = Triple(id, variant, confirmed) },
                )
            }
        }

        awaitTag("$PROFILE_ROW_TAG-p1")
        compose.onNodeWithTag("$PROFILE_ROW_TAG-p1").performTouchInput { longClick() }
        awaitTag(actionTag("p1", SyncVariant.COPY_TO_SERVER))
        compose.onNodeWithTag(actionTag("p1", SyncVariant.COPY_TO_SERVER)).performClick()

        awaitText(SYNC_BUSY_MESSAGE)
        assertEquals(null, ran)
    }

    @Test
    fun `a requested-but-not-yet-started run blocks a second run`() {
        runBlocking { repository.save(profile(id = "p1", name = "My Server")) }
        engine.beginRun()
        var ran: Triple<String, SyncVariant, Boolean>? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesScreen(
                    onOpenProfile = {},
                    onStartSync = {},
                    onRunVariant = { id, variant, confirmed -> ran = Triple(id, variant, confirmed) },
                )
            }
        }

        awaitTag("$PROFILE_ROW_TAG-p1")
        compose.onNodeWithTag("$PROFILE_ROW_TAG-p1").performTouchInput { longClick() }
        awaitTag(actionTag("p1", SyncVariant.COPY_TO_SERVER))
        compose.onNodeWithTag(actionTag("p1", SyncVariant.COPY_TO_SERVER)).performClick()

        awaitText(SYNC_BUSY_MESSAGE)
        assertEquals(null, ran)
    }

    @Test
    fun `confirming a mirror that became busy while the dialog was open is blocked`() {
        val conflicting = profile(id = "p1", name = "My Server")
        var syncActive by mutableStateOf(false)
        var ran: Triple<String, SyncVariant, Boolean>? = null

        compose.setContent {
            UnisonDroidTheme {
                ProfilesContent(
                    profiles = listOf(conflicting),
                    onOpenProfile = {},
                    onStartSync = {},
                    onRunVariant = { id, variant, confirmed -> ran = Triple(id, variant, confirmed) },
                    syncActive = syncActive,
                )
            }
        }

        awaitTag("$PROFILE_ROW_TAG-p1")
        compose.onNodeWithTag("$PROFILE_ROW_TAG-p1").performTouchInput { longClick() }
        awaitTag(actionTag("p1", SyncVariant.MIRROR_TO_SERVER))
        compose.onNodeWithTag(actionTag("p1", SyncVariant.MIRROR_TO_SERVER)).performClick()
        awaitTag(MIRROR_CONFIRM_TAG)

        compose.runOnIdle { syncActive = true }
        compose.onNodeWithTag(MIRROR_CONFIRM_TAG).performClick()

        awaitText(SYNC_BUSY_MESSAGE)
        assertEquals(null, ran)
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun actionTag(profileId: String, variant: SyncVariant): String =
        "$PROFILE_ACTION_PREFIX$profileId-${variant.name}"

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun expectedLastSyncLabel(at: Long): String =
        "Last sync: " + LOCAL_TIME_FORMATTER.format(
            Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()),
        )

    private fun profile(id: String, name: String): Profile = Profile(
        id = id,
        name = name,
        localRoot = "/storage/emulated/0/Documents",
        remoteRoot = "/home/user/Documents",
        host = "server.example.com",
        user = "user",
        sshKeyId = "key1",
        lastSyncedAt = 1_700_000_000_000L,
        lastResult = SyncResult.OK,
    )

    private companion object {
        const val GETTING_STARTED_HINT = "set up Unison"
        val LOCAL_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
