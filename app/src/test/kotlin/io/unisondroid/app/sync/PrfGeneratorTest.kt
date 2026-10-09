package io.unisondroid.app.sync

import io.unisondroid.app.data.Profile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrfGeneratorTest {

    private fun profile(
        localRoot: String = "/storage/emulated/0/Documents",
        ignorePatterns: List<String> = emptyList(),
        advancedPrefs: String = "",
    ) = Profile(
        id = "p1",
        name = "Phone",
        localRoot = localRoot,
        remoteRoot = "/srv/sync",
        host = "server.example",
        sshPort = 22,
        user = "diamond",
        sshKeyId = "key-1",
        ignorePatterns = ignorePatterns,
        advancedPrefs = advancedPrefs,
    )

    @Test
    fun `roots come first, local root then socket root`() {
        val out = PrfGenerator.generate(profile(), localSocketPort = 22334)

        val lines = out.lines()
        assertEquals("root = /storage/emulated/0/Documents", lines[0])
        assertEquals("root = socket://127.0.0.1:22334", lines[1])
    }

    @Test
    fun `defaults block contains Android values`() {
        val out = PrfGenerator.generate(profile(), localSocketPort = 22334)

        val lines = out.lines()
        assertEquals(listOf("perms = 0", "links = false", "fat = true"), lines.subList(2, 5))
    }

    @Test
    fun `each ignore pattern emits ignore = Path line`() {
        val out = PrfGenerator.generate(
            profile(ignorePatterns = listOf("*.tmp", ".thumbnails", "My Folder/Keep*")),
            localSocketPort = 22334,
        )

        assertTrue(out.contains("ignore = Path *.tmp\n"))
        assertTrue(out.contains("ignore = Path .thumbnails\n"))
        assertTrue(out.contains("ignore = Path My Folder/Keep*\n"))
    }

    @Test
    fun `advanced block appended after ignores`() {
        val out = PrfGenerator.generate(
            profile(ignorePatterns = listOf("*.bak"), advancedPrefs = "fastcheck = false\n"),
            localSocketPort = 22334,
        )

        assertTrue(out.indexOf("ignore = Path *.bak") < out.indexOf("fastcheck = false"))
    }

    @Test
    fun `full output with unicode and space path is byte-identical`() {
        val p = profile(
            localRoot = "/storage/emulated/0/My Folder/中文",
            ignorePatterns = listOf("*.tmp", ".thumbnails"),
            advancedPrefs = "fastcheck = false\n",
        )
        val expected = """
            root = /storage/emulated/0/My Folder/中文
            root = socket://127.0.0.1:22334
            perms = 0
            links = false
            fat = true
            ignore = Path *.tmp
            ignore = Path .thumbnails
            fastcheck = false
        """.trimIndent() + "\n"

        assertEquals(expected, PrfGenerator.generate(p, localSocketPort = 22334))
    }

    @Test
    fun `empty ignores and advanced leave no blank residue`() {
        val expected = "root = /storage/emulated/0/Documents\n" +
            "root = socket://127.0.0.1:22334\n" +
            "perms = 0\n" +
            "links = false\n" +
            "fat = true\n"

        assertEquals(expected, PrfGenerator.generate(profile(), localSocketPort = 22334))
    }

    @Test
    fun `advanced without trailing newline still ends with single newline`() {
        val out = PrfGenerator.generate(profile(advancedPrefs = "fastcheck = false"), localSocketPort = 22334)

        assertTrue(out.endsWith("fastcheck = false\n"))
        assertFalse(out.endsWith("\n\n"))
    }
}
