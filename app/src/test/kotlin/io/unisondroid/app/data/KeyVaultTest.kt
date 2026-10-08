package io.unisondroid.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `generate returns loadable key pair`() = runTest {
        val vault = KeyVault(JsonStore(dir), cipher)

        val key = vault.generate("laptop")

        assertTrue(key.publicKey.startsWith("ssh-ed25519 "))
        val parts = key.publicKey.split(" ")
        assertEquals(3, parts.size)
        assertEquals("laptop", parts[2])
        assertTrue(Base64.getDecoder().decode(parts[1]).size > 0)

        val pem = String(cipher.decrypt(Base64.getDecoder().decode(key.encryptedPrivateBase64)), Charsets.UTF_8)
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertNotNull(SSHClient().loadKeys(pem, null, null))
    }

    @Test
    fun `round-trip via privateKeyPem matches`() = runTest {
        val vault = KeyVault(JsonStore(dir), cipher)
        val key = vault.generate("laptop")

        val pem = vault.privateKeyPem(key.id)

        val stored = String(cipher.decrypt(Base64.getDecoder().decode(key.encryptedPrivateBase64)), Charsets.UTF_8)
        assertEquals(stored, pem)
        assertNotNull(SSHClient().loadKeys(pem, null, null))

        val reloaded = KeyVault(JsonStore(dir), cipher)
        assertEquals(pem, reloaded.privateKeyPem(key.id))
    }

    @Test
    fun `import of garbage throws KeyVaultException`() = runTest {
        val vault = KeyVault(JsonStore(dir), cipher)

        assertThrows(KeyVaultException::class.java) {
            runBlocking { vault.importOpenSsh("bad", "this is not a key") }
        }

        assertTrue(vault.keys().isEmpty())
    }

    @Test
    fun `import of valid OpenSSH ed25519 key succeeds`() = runTest {
        val vault = KeyVault(JsonStore(dir), cipher)
        val (pem, publicBlob) = generateEd25519OpenSshKeyPem("seed-key")

        val key = vault.importOpenSsh("server", pem)

        assertEquals("server", key.name)
        assertEquals("server", key.publicKey.split(" ")[2])
        assertEquals("ssh-ed25519 ${Base64.getEncoder().encodeToString(publicBlob)} server", key.publicKey)
        assertEquals(pem, vault.privateKeyPem(key.id))
    }

    @Test
    fun `keys lists saved keys`() = runTest {
        val vault = KeyVault(JsonStore(dir), cipher)

        assertTrue(vault.keys().isEmpty())

        val generated = vault.generate("laptop")
        val importedPem = generateEd25519OpenSshKeyPem("seed-key").first
        val imported = vault.importOpenSsh("server", importedPem)

        assertEquals(listOf(generated, imported), vault.keys())

        val reloaded = KeyVault(JsonStore(dir), cipher)
        assertEquals(listOf(generated, imported), reloaded.keys())
    }

    private class FakeCipher : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)
    }
}
