package io.unisondroid.app.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HostKeyStoreTest {

    @Test
    fun `verify returns UNKNOWN for unseen host`(@TempDir dir: File) = runTest {
        val store = HostKeyStore(JsonStore(dir))

        assertEquals(TofuVerdict.UNKNOWN, store.verify("server.example", 22, "SHA256:aaaa"))
    }

    @Test
    fun `approve then verify same fingerprint returns APPROVED`(@TempDir dir: File) = runTest {
        val store = HostKeyStore(JsonStore(dir))

        store.approve("server.example", 22, "SHA256:bbbb")

        assertEquals(TofuVerdict.APPROVED, store.verify("server.example", 22, "SHA256:bbbb"))
    }

    @Test
    fun `different fingerprint after approval returns CHANGED`(@TempDir dir: File) = runTest {
        val store = HostKeyStore(JsonStore(dir))

        store.approve("server.example", 22, "SHA256:bbbb")

        assertEquals(TofuVerdict.CHANGED, store.verify("server.example", 22, "SHA256:cccc"))
    }

    @Test
    fun `known returns null before approval and matching entry after`(@TempDir dir: File) = runTest {
        val store = HostKeyStore(JsonStore(dir))

        assertNull(store.known("server.example", 22))
        store.approve("server.example", 22, "SHA256:bbbb")

        val known = store.known("server.example", 22)
        assertNotNull(known)
        assertEquals("server.example", known?.host)
        assertEquals(22, known?.port)
        assertEquals("SHA256:bbbb", known?.fingerprint)
        assertTrue((known?.approvedAt ?: 0L) > 0L)
    }

    @Test
    fun `entries persist across store reload and distinct ports are independent`(@TempDir dir: File) = runTest {
        HostKeyStore(JsonStore(dir)).approve("server.example", 22, "SHA256:bbbb")

        val reloaded = HostKeyStore(JsonStore(dir))
        assertEquals("SHA256:bbbb", reloaded.known("server.example", 22)?.fingerprint)
        assertEquals(TofuVerdict.APPROVED, reloaded.verify("server.example", 22, "SHA256:bbbb"))
        assertEquals(TofuVerdict.UNKNOWN, reloaded.verify("server.example", 2222, "SHA256:bbbb"))
    }

    @Test
    fun `re-approval replaces the stored fingerprint`(@TempDir dir: File) = runTest {
        val store = HostKeyStore(JsonStore(dir))

        store.approve("server.example", 22, "SHA256:bbbb")
        store.approve("server.example", 22, "SHA256:dddd")

        assertEquals("SHA256:dddd", store.known("server.example", 22)?.fingerprint)
        assertEquals(TofuVerdict.APPROVED, store.verify("server.example", 22, "SHA256:dddd"))
        assertEquals(TofuVerdict.CHANGED, store.verify("server.example", 22, "SHA256:bbbb"))
    }
}
