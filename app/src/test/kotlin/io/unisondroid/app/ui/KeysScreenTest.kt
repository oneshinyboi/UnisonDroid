package io.unisondroid.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.service.ServiceLocator
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeysScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var vault: KeyVault

    @Before
    fun setUp() {
        val dir = Files.createTempDirectory("keys-screen-test").toFile()
        vault = KeyVault(JsonStore(dir), XorCipher)
        ServiceLocator.reset()
        ServiceLocator.keysProvider = { vault }
    }

    @After
    fun tearDown() {
        ServiceLocator.keysProvider = ServiceLocator.defaultKeysProvider
        ServiceLocator.reset()
    }

    @Test
    fun `generate lists the new key with an ssh-ed25519 public line`() {
        compose.setContent { UnisonDroidTheme { KeysScreen() } }

        compose.onNodeWithTag(KEYS_GENERATE_TAG).performClick()
        compose.onNodeWithTag(KEYS_GENERATE_NAME_FIELD).performTextInput("laptop")
        compose.onNodeWithTag(KEYS_GENERATE_CONFIRM_TAG).performClick()

        awaitText("ssh-ed25519")
        compose.onNodeWithText("laptop").assertExists()
        compose.onNodeWithText("ssh-ed25519", substring = true).assertExists()
    }

    @Test
    fun `copy places the public key on the clipboard`() {
        runBlocking { vault.generate("laptop") }

        compose.setContent { UnisonDroidTheme { KeysScreen() } }
        awaitTag(KEYS_COPY_PUBLIC_TAG)

        compose.onNodeWithTag(KEYS_COPY_PUBLIC_TAG).performClick()

        val clipboard = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertTrue("clipboard must contain the public key", copied?.startsWith("ssh-ed25519 ") == true)
    }

    @Test
    fun `import with invalid PEM shows an error snackbar and adds no key`() {
        compose.setContent { UnisonDroidTheme { KeysScreen() } }

        compose.onNodeWithTag(KEYS_IMPORT_TAG).performClick()
        compose.onNodeWithTag(KEYS_IMPORT_PEM_FIELD).performTextInput("this is not a key")
        compose.onNodeWithTag(KEYS_IMPORT_CONFIRM_TAG).performClick()

        awaitText(IMPORT_ERROR_MESSAGE)
        compose.onNodeWithText(IMPORT_ERROR_MESSAGE).assertExists()
        assertTrue("no key may be added for an invalid PEM", runBlocking { vault.keys() }.isEmpty())
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private object XorCipher : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)
    }
}
