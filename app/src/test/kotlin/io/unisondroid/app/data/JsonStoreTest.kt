package io.unisondroid.app.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JsonStoreTest {

    @Test
    fun `write then read round-trips`(@TempDir dir: File) = runTest {
        val key = SshKey(
            id = "key-1",
            name = "Phone key",
            publicKey = "ssh-ed25519 AAAA",
            encryptedPrivateBase64 = "ZW5jcnlwdGVk",
        )
        JsonStore(dir).write("keys", key)

        val readBack = JsonStore(dir).read<SshKey>("keys")

        assertEquals(key, readBack)
    }

    @Test
    fun `read of missing file returns null`(@TempDir dir: File) = runTest {
        val store = JsonStore(dir)

        assertNull(store.read<SshKey>("missing"))
    }

    @Test
    fun `write is atomic (no temp files left)`(@TempDir dir: File) = runTest {
        val store = JsonStore(dir)

        store.write("keys", SshKey(id = "key-1", name = "Phone key", publicKey = "pk", encryptedPrivateBase64 = "priv"))

        val fileNames = dir.listFiles()!!.map { it.name }
        assertTrue(File(dir, "keys.json").isFile)
        assertEquals(listOf("keys.json"), fileNames)
    }
}
