package io.unisondroid.app.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class UnisonInfoTest {

    @Test
    fun `bundled versions match native-unison-versions file`() {
        val tags = File(repoRoot(), "native/unison-versions.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefix("v") }
        assertEquals(tags.toSet(), UnisonInfo.BUNDLED.map { it.version }.toSet())
    }

    @Test
    fun `file names follow the encoding rule`() {
        UnisonInfo.BUNDLED.forEach { b ->
            assertEquals("libunison_${b.version.replace('.', '_')}.so", b.fileName)
        }
    }

    @Test
    fun `forVersion falls back to default for unknown or blank`() {
        assertEquals(UnisonInfo.DEFAULT, UnisonInfo.forVersion(""))
        assertEquals(UnisonInfo.DEFAULT, UnisonInfo.forVersion("1.2.3"))
        assertEquals("2.53.8", UnisonInfo.forVersion("2.53.8").version)
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "native/unison-versions.txt").isFile) {
            dir = dir.parentFile ?: error("native/unison-versions.txt not found from ${System.getProperty("user.dir")}")
        }
        return dir
    }
}
