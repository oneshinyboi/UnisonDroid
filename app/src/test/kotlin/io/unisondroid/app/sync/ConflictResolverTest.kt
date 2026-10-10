package io.unisondroid.app.sync

import io.unisondroid.app.data.Profile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConflictResolverTest {

    private fun profile(
        localRoot: String = "/storage/emulated/0/Sync",
        remoteRoot: String = "/srv/sync",
    ) = Profile(
        id = "p1",
        name = "Phone",
        localRoot = localRoot,
        remoteRoot = remoteRoot,
        host = "server.example",
        user = "diamond",
        sshKeyId = "key-1",
    )

    @Test
    fun `keep local and remote emit preferpartial with exact roots`() {
        val prefs = ConflictResolver.resolutionPreferences(
            profile(),
            mapOf("a.txt" to Resolution.KEEP_LOCAL, "b/c.txt" to Resolution.KEEP_REMOTE),
        )
        assertTrue(prefs.contains("preferpartial = Path a.txt -> /storage/emulated/0/Sync"))
        assertTrue(prefs.contains("preferpartial = Path b/c.txt -> ssh://diamond@server.example//srv/sync"))
    }

    @Test
    fun `glob metacharacters in a path are escaped`() {
        val prefs = ConflictResolver.resolutionPreferences(
            profile(),
            mapOf("a[1]*.txt" to Resolution.KEEP_LOCAL),
        )
        assertEquals(
            listOf("""preferpartial = Path a\[1\]\*.txt -> /storage/emulated/0/Sync"""),
            prefs,
        )
    }

    @Test
    fun `keep both prefers newer and sets copyonconflict once`() {
        val prefs = ConflictResolver.resolutionPreferences(
            profile(),
            mapOf("a" to Resolution.KEEP_BOTH, "b" to Resolution.KEEP_BOTH),
        )
        assertEquals(1, prefs.count { it == "copyonconflict = true" })
        assertTrue(prefs.contains("preferpartial = Path a -> newer"))
        assertTrue(prefs.contains("preferpartial = Path b -> newer"))
    }

    @Test
    fun `skip emits nothing for that path`() {
        assertTrue(
            ConflictResolver.resolutionPreferences(profile(), mapOf("a" to Resolution.SKIP)).isEmpty(),
        )
    }

    @Test
    fun `empty decisions map yields an empty list`() {
        assertTrue(ConflictResolver.resolutionPreferences(profile(), emptyMap()).isEmpty())
    }

    @Test
    fun `path with spaces and unicode round trips unescaped`() {
        val prefs = ConflictResolver.resolutionPreferences(
            profile(),
            mapOf("My Folder/中文 name.txt" to Resolution.KEEP_LOCAL),
        )
        assertEquals(
            listOf("preferpartial = Path My Folder/中文 name.txt -> /storage/emulated/0/Sync"),
            prefs,
        )
    }

    @Test
    fun `every glob metacharacter is escaped`() {
        val prefs = ConflictResolver.resolutionPreferences(
            profile(),
            mapOf("""a\b*c?d[e]f{g},h.txt""" to Resolution.KEEP_REMOTE),
        )
        assertEquals(
            listOf("""preferpartial = Path a\\b\*c\?d\[e\]f\{g\}\,h.txt -> ssh://diamond@server.example//srv/sync"""),
            prefs,
        )
    }

    @Test
    fun `lines follow decisions map iteration order`() {
        val decisions = linkedMapOf(
            "z.txt" to Resolution.KEEP_LOCAL,
            "a.txt" to Resolution.KEEP_REMOTE,
            "m.txt" to Resolution.KEEP_BOTH,
        )
        val prefs = ConflictResolver.resolutionPreferences(profile(), decisions)

        assertEquals(
            listOf(
                "preferpartial = Path z.txt -> /storage/emulated/0/Sync",
                "preferpartial = Path a.txt -> ssh://diamond@server.example//srv/sync",
                "preferpartial = Path m.txt -> newer",
                "copyonconflict = true",
            ),
            prefs,
        )
    }

    @Test
    fun `skip mixed with other decisions only omits the skipped path`() {
        val prefs = ConflictResolver.resolutionPreferences(
            profile(),
            mapOf(
                "a.txt" to Resolution.KEEP_LOCAL,
                "b.txt" to Resolution.SKIP,
                "c.txt" to Resolution.KEEP_BOTH,
            ),
        )
        assertFalse(prefs.any { it.contains("Path b.txt") })
        assertTrue(prefs.contains("preferpartial = Path a.txt -> /storage/emulated/0/Sync"))
        assertTrue(prefs.contains("preferpartial = Path c.txt -> newer"))
        assertTrue(prefs.contains("copyonconflict = true"))
    }
}
