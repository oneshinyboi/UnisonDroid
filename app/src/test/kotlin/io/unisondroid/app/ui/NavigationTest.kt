package io.unisondroid.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.unisondroid.app.data.ConflictRecord
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.FakeSshTool
import io.unisondroid.app.sync.OutputParser
import io.unisondroid.app.sync.SyncEngine
import io.unisondroid.app.sync.SyncState
import io.unisondroid.app.sync.UnisonRunner
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        val dir = Files.createTempDirectory("navigation-test").toFile()
        val store = JsonStore(dir)
        repository = ProfileRepository(store)
        ServiceLocator.reset()
        ServiceLocator.profilesProvider = { ProfileRepository(store) }
        ServiceLocator.keysProvider = { KeyVault(store, XorCipher, FakeSshTool()) }
        ServiceLocator.engineProvider = { NoopEngine() }
    }

    @After
    fun tearDown() {
        ServiceLocator.profilesProvider = ServiceLocator.defaultProfilesProvider
        ServiceLocator.keysProvider = ServiceLocator.defaultKeysProvider
        ServiceLocator.engineProvider = ServiceLocator.defaultEngineProvider
        ServiceLocator.reset()
    }

    @Test
    fun `keys action on profiles navigates to the keys screen`() {
        compose.setContent { UnisonDroidTheme { UnisonDroidNavHost() } }

        awaitTag(PROFILES_KEYS_ACTION_TAG)
        compose.onNodeWithTag(PROFILES_KEYS_ACTION_TAG).performClick()

        awaitTag(KEYS_GENERATE_TAG)
        compose.onNodeWithTag(KEYS_GENERATE_TAG).assertExists()
    }

    @Test
    fun `about action on profiles navigates to the about screen`() {
        compose.setContent { UnisonDroidTheme { UnisonDroidNavHost() } }

        awaitTag(PROFILES_ABOUT_ACTION_TAG)
        compose.onNodeWithTag(PROFILES_ABOUT_ACTION_TAG).performClick()

        awaitTag(ABOUT_VERSION_TAG)
        compose.onNodeWithTag(ABOUT_VERSION_TAG).assertExists()
    }

    @Test
    fun `settings action on profiles navigates to the settings screen`() {
        compose.setContent { UnisonDroidTheme { UnisonDroidNavHost() } }

        awaitTag(PROFILES_SETTINGS_ACTION_TAG)
        compose.onNodeWithTag(PROFILES_SETTINGS_ACTION_TAG).performClick()

        awaitTag(SETTINGS_TOGGLE_TAG)
        compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).assertExists()
    }

    @Test
    fun `resolve route renders the resolve-conflicts screen for a profile with conflicts`() {
        runBlocking {
            repository.save(
                Profile(
                    id = "p1",
                    name = "Phone",
                    localRoot = "/storage/emulated/0/Unison",
                    remoteRoot = "/home/user/sync",
                    host = "server.example.com",
                    user = "user",
                    sshKeyId = "key1",
                    lastConflicts = listOf(
                        ConflictRecord("notes/plan.txt", "conflicting updates", resolvable = true),
                    ),
                ),
            )
        }

        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            UnisonDroidTheme { UnisonDroidNavHost(navController) }
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(Routes.resolve("p1")) }

        awaitTag("${RESOLVE_ROW_PREFIX}notes/plan.txt")
        compose.onNodeWithTag("${RESOLVE_ROW_PREFIX}notes/plan.txt").assertExists()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private object XorCipher : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)
    }
}

private class NoopEngine : SyncEngine(
    BinaryLocator(File("/nonexistent/native-lib")),
    ProfileRepository(JsonStore(File("/nonexistent/data"))),
    KeyVault(JsonStore(File("/nonexistent/data")), NavigationIdentityCipher, FakeSshTool()),
    FakeSshTool(),
    { UnisonRunner(it) },
    { OutputParser() },
    File("/nonexistent/unison"),
    File("/nonexistent/ssh"),
    Clock.systemUTC(),
) {
    val states = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = states.asStateFlow()
}

private object NavigationIdentityCipher : KeyCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain
    override fun decrypt(blob: ByteArray): ByteArray = blob
}
