package io.unisondroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.TinkKeyCipher
import io.unisondroid.app.sync.ProcessSshTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TinkKeyCipherTest {

    @Test
    fun encryptDecrypt_roundTrips() {
        val cipher = TinkKeyCipher(InstrumentationRegistry.getInstrumentation().targetContext)
        val plain = "hello tink".toByteArray() + ByteArray(32) { it.toByte() }
        assertArrayEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }

    @Test
    fun encrypt_usesFreshCiphertextPerCall() {
        val cipher = TinkKeyCipher(InstrumentationRegistry.getInstrumentation().targetContext)
        val plain = "same input".toByteArray()
        assertFalse(cipher.encrypt(plain).contentEquals(cipher.encrypt(plain)))
    }

    @Test
    fun keyVault_generate_privateKeyPemRoundTrips() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "tink_test_${System.nanoTime()}").apply { mkdirs() }
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val tool = ProcessSshTool(
            keygen = File(nativeDir, "libssh-keygen.so"),
            keyscan = File(nativeDir, "libssh-keyscan.so"),
            workDir = context.cacheDir,
        )
        val vault = KeyVault(JsonStore(dir), TinkKeyCipher(context), tool)

        val key = vault.generate("e2e-key")
        assertTrue("public key must be OpenSSH ed25519", key.publicKey.startsWith("ssh-ed25519 "))

        val pem = vault.privateKeyPem(key.id)
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(pem.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))
        assertEquals("decryption must be deterministic across reads", pem, vault.privateKeyPem(key.id))
    }
}
