package io.unisondroid.app.ui

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.unisondroid.app.data.AppSettings
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.SyncResult
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.service.SyncScheduler
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
        val dir = Files.createTempDirectory("profiles-screen-test").toFile()
        repository = ProfileRepository(JsonStore(dir))
        ServiceLocator.reset()
        ServiceLocator.profilesProvider = { repository }
    }

    @After
    fun tearDown() {
        ServiceLocator.profilesProvider = ServiceLocator.defaultProfilesProvider
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
        compose.onNodeWithText("Last sync: 2023-11-14 22:13").assertExists()
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

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

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
    }
}
