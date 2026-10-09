package io.unisondroid.app.data

import io.unisondroid.app.sync.FakeSshTool
import io.unisondroid.app.sync.GeneratedKey
import io.unisondroid.app.sync.SshToolException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

class KeyVaultTest {

    @TempDir
    lateinit var dir: File

    private val cipher = FakeCipher()

    @Test
    fun `generate persists and round-trips via privateKeyPem`() = runTest {
        val tool = FakeSshTool()
        tool.generated = GeneratedKey(
            privatePem = generateEd25519OpenSshKeyPem("laptop").first,
            publicKeyLine = "ssh-ed25519 AAAAZ2VuZXJhdGVk laptop",
        )
        val vault = KeyVault(JsonStore(dir), cipher, tool)

        val key = vault.generate("laptop")

        assertEquals(listOf("laptop"), tool.generateCalls)
        assertEquals("ssh-ed25519 AAAAZ2VuZXJhdGVk laptop", key.publicKey)
        assertEquals(tool.generated.privatePem, vault.privateKeyPem(key.id))
        val stored = String(cipher.decrypt(Base64.getDecoder().decode(key.encryptedPrivateBase64)), Charsets.UTF_8)
        assertEquals(tool.generated.privatePem, stored)

        val reloaded = KeyVault(JsonStore(dir), cipher, FakeSshTool())
        assertEquals(tool.generated.privatePem, reloaded.privateKeyPem(key.id))
    }

    @Test
    fun `import persists derived public key`() = runTest {
        val tool = FakeSshTool()
        val vault = KeyVault(JsonStore(dir), cipher, tool)
        val pem = generateEd25519OpenSshKeyPem("seed").first

        val key = vault.importOpenSsh("server", pem)

        assertEquals(listOf(pem), tool.deriveCalls)
        assertEquals("server", key.name)
        assertEquals("ssh-ed25519 BBBBZGVyaXZlZA== server", key.publicKey)
        assertEquals(pem, vault.privateKeyPem(key.id))
    }

    @Test
    fun `import rejects key keygen cannot read`() = runTest {
        val tool = FakeSshTool()
        tool.deriveException = SshToolException("incorrect passphrase or malformed key")
        val vault = KeyVault(JsonStore(dir), cipher, tool)

        assertThrows(KeyVaultException::class.java) {
            kotlinx.coroutines.runBlocking { vault.importOpenSsh("bad", "this is not a key") }
        }

        assertTrue(vault.keys().isEmpty())
    }

    @Test
    fun `decryption failure wraps in KeyVaultException`() = runTest {
        val throwing = object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray = plain
            override fun decrypt(blob: ByteArray): ByteArray = throw IllegalStateException("corrupt blob")
        }
        val vault = KeyVault(JsonStore(dir), throwing, FakeSshTool())
        val key = vault.generate("laptop")

        assertThrows(KeyVaultException::class.java) {
            kotlinx.coroutines.runBlocking { vault.privateKeyPem(key.id) }
        }
    }

    @Test
    fun `keys lists saved keys across reloads`() = runTest {
        val tool = FakeSshTool()
        val vault = KeyVault(JsonStore(dir), cipher, tool)

        assertTrue(vault.keys().isEmpty())

        val generated = vault.generate("laptop")
        val imported = vault.importOpenSsh("server", "pem-body")

        assertEquals(listOf(generated, imported), vault.keys())

        val reloaded = KeyVault(JsonStore(dir), cipher, FakeSshTool())
        assertEquals(listOf(generated, imported), reloaded.keys())
    }

    private class FakeCipher : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)
    }
}
