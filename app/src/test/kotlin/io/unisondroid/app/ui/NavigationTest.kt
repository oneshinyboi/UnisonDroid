package io.unisondroid.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.sync.FakeSshTool
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val dir = Files.createTempDirectory("navigation-test").toFile()
        val store = JsonStore(dir)
        ServiceLocator.reset()
        ServiceLocator.profilesProvider = { ProfileRepository(store) }
        ServiceLocator.keysProvider = { KeyVault(store, XorCipher, FakeSshTool()) }
    }

    @After
    fun tearDown() {
        ServiceLocator.profilesProvider = ServiceLocator.defaultProfilesProvider
        ServiceLocator.keysProvider = ServiceLocator.defaultKeysProvider
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
