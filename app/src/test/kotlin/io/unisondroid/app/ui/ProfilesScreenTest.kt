package io.unisondroid.app.ui

import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.SyncResult
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
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
