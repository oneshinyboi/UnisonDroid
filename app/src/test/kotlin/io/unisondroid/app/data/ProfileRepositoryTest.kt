package io.unisondroid.app.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProfileRepositoryTest {

    private fun profile(id: String = "", name: String = "Phone") = Profile(
        id = id,
        name = name,
        localRoot = "/storage/emulated/0/Unison",
        remoteRoot = "/home/user/sync",
        host = "server.example.com",
        user = "user",
        sshKeyId = "key-1",
    )

    @Test
    fun `save inserts and upserts`(@TempDir dir: File) = runTest {
        val repo = ProfileRepository(JsonStore(dir))

        repo.save(profile(name = "Phone"))
        val inserted = repo.profiles().single()
        assertEquals("Phone", inserted.name)

        repo.save(inserted.copy(name = "Renamed"))
        val upserted = repo.profiles().single()
        assertEquals(inserted.id, upserted.id)
        assertEquals("Renamed", upserted.name)
    }

    @Test
    fun `delete removes`(@TempDir dir: File) = runTest {
        val repo = ProfileRepository(JsonStore(dir))
        repo.save(profile())
        val id = repo.profiles().single().id

        repo.delete(id)

        assertTrue(repo.profiles().isEmpty())
        assertNull(repo.get(id))
    }

    @Test
    fun `get returns by id`(@TempDir dir: File) = runTest {
        val repo = ProfileRepository(JsonStore(dir))
        repo.save(profile(name = "Phone"))
        repo.save(profile(name = "Tablet"))
        val phone = repo.profiles().first { it.name == "Phone" }

        assertEquals(phone, repo.get(phone.id))
        assertNull(repo.get("does-not-exist"))
    }

    @Test
    fun `ids are slugs`(@TempDir dir: File) = runTest {
        val repo = ProfileRepository(JsonStore(dir))

        repeat(10) { repo.save(profile()) }

        val ids = repo.profiles().map { it.id }
        assertEquals(10, ids.size)
        ids.forEach { id ->
            assertTrue(Regex("[a-z0-9-]{8}").matches(id), "expected [a-z0-9-]{8} slug, got: $id")
        }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `profiles saved before the schedule fields existed still load`(@TempDir dir: File) = runTest {
        File(dir, "profiles.json").writeText(
            """[{"id":"p1","name":"Phone","localRoot":"/l","remoteRoot":"/r","host":"h","user":"u","sshKeyId":"k"}]""",
        )

        val loaded = ProfileRepository(JsonStore(dir)).profiles().single()

        assertFalse(loaded.autoSyncEnabled)
        assertEquals(60, loaded.autoSyncIntervalMinutes)
    }
}
