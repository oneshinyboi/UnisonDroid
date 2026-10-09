package io.unisondroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.data.Profile
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.BinaryStatus
import io.unisondroid.app.sync.PrfGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LocalSyncE2eTest {

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    }

    @Test
    fun localSync_mirrorsChangesBothWays_andIsIdempotent() {
        val binary = locateBinary()
        val base = newBaseDir()
        val rootA = File(base, "a").apply { mkdirs() }
        val rootB = File(base, "b").apply { mkdirs() }
        val profileDir = File(base, "unison").apply { mkdirs() }
        val generated = writeGeneratedProfile(profileDir, PROFILE, rootA, rootB)
        assertTrue(
            "production profile must disable unison's hard-link archive lock, got:\n$generated",
            generated.contains("ignorelocks = true"),
        )

        File(rootA, "hello.txt").writeText("hello world")
        File(rootA, "sub").mkdirs()
        File(rootA, "sub/nested.txt").writeText("nested")

        val first = runUnison(binary, profileDir)
        assertEquals("first sync failed:\n${first.output}", 0, first.exit)
        assertEquals(snapshot(rootA), snapshot(rootB))
        assertEquals("hello world", File(rootB, "hello.txt").readText())
        assertEquals("nested", File(rootB, "sub/nested.txt").readText())

        File(rootA, "hello.txt").writeText("hello world v2")
        File(rootA, "sub/nested.txt").delete()
        File(rootA, "added.txt").writeText("added on A")

        val second = runUnison(binary, profileDir)
        assertEquals("A->B sync failed:\n${second.output}", 0, second.exit)
        assertEquals(snapshot(rootA), snapshot(rootB))
        assertFalse("deletion on A should propagate", File(rootB, "sub/nested.txt").exists())
        assertEquals("added on A", File(rootB, "added.txt").readText())
        assertEquals("hello world v2", File(rootB, "hello.txt").readText())

        File(rootB, "from_b.txt").writeText("made on B")
        File(rootB, "hello.txt").writeText("hello from B")
        File(rootB, "added.txt").delete()

        val third = runUnison(binary, profileDir)
        assertEquals("B->A sync failed:\n${third.output}", 0, third.exit)
        assertEquals(snapshot(rootA), snapshot(rootB))
        assertEquals("made on B", File(rootA, "from_b.txt").readText())
        assertEquals("hello from B", File(rootA, "hello.txt").readText())
        assertFalse("deletion on B should propagate", File(rootA, "added.txt").exists())

        val before = snapshot(rootA)
        val fourth = runUnison(binary, profileDir)
        assertEquals("idempotent re-run failed:\n${fourth.output}", 0, fourth.exit)
        assertEquals(before, snapshot(rootA))
        assertEquals(snapshot(rootA), snapshot(rootB))
        assertTrue(
            "re-run should have nothing to do, got:\n${fourth.output}",
            fourth.output.contains("Nothing to do"),
        )
    }

    @Test
    fun mtimeOnlySkew_causesNoTransferOnResync() {
        val binary = locateBinary()
        val base = newBaseDir()
        val rootA = File(base, "a").apply { mkdirs() }
        val rootB = File(base, "b").apply { mkdirs() }
        val profileDir = File(base, "unison").apply { mkdirs() }
        writeGeneratedProfile(profileDir, PROFILE, rootA, rootB)

        val fileA = File(rootA, "stamp.txt")
        fileA.writeText("stable content")
        File(rootA, "other.txt").writeText("other")

        val first = runUnison(binary, profileDir)
        assertEquals("initial sync failed:\n${first.output}", 0, first.exit)
        assertEquals(snapshot(rootA), snapshot(rootB))

        val fileB = File(rootB, "stamp.txt")
        val bMtime = fileB.lastModified()
        fileA.setLastModified(bMtime + 1000)

        val bBefore = snapshot(rootB)
        val second = runUnison(binary, profileDir)
        assertEquals("mtime-skew re-sync failed:\n${second.output}", 0, second.exit)
        assertEquals("contents must remain mirrored", snapshot(rootA), snapshot(rootB))
        assertEquals("B must not be rewritten by an mtime-only change", bBefore, snapshot(rootB))
        assertEquals("B mtime must be untouched (times = false)", bMtime, fileB.lastModified())
        assertTrue(
            "an mtime-only change must transfer nothing, got:\n${second.output}",
            second.output.contains("Nothing to do"),
        )
    }

    private fun locateBinary(): File {
        val nativeDir = File(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir)
        val status = BinaryLocator(nativeDir).locate()
        assertTrue("libunison.so must be present in $nativeDir", status is BinaryStatus.Available)
        return (status as BinaryStatus.Available).path
    }

    private fun newBaseDir(): File =
        File(cacheDir, "e2e_${System.nanoTime()}").apply { mkdirs() }

    private fun writeGeneratedProfile(
        profileDir: File,
        name: String,
        rootA: File,
        rootB: File,
    ): String {
        val profile = Profile(
            id = "e2e",
            name = "e2e",
            localRoot = rootA.absolutePath,
            remoteRoot = rootB.absolutePath,
            host = "localhost",
            user = "e2e",
            sshKeyId = "e2e",
        )
        val generated = PrfGenerator.generate(profile, localSocketPort = SOCKET_PORT)
        val localLocal = generated.replace(
            "root = socket://127.0.0.1:$SOCKET_PORT",
            "root = ${rootB.absolutePath}",
        )
        check(localLocal != generated) { "PrfGenerator no longer emits the expected socket root" }
        File(profileDir, "$name.prf").writeText(localLocal)
        return localLocal
    }

    private data class RunResult(val exit: Int, val output: String)

    private fun runUnison(binary: File, profileDir: File): RunResult {
        val process = ProcessBuilder(listOf(binary.absolutePath, PROFILE, "-batch"))
            .redirectErrorStream(true)
            .directory(cacheDir)
            .apply {
                environment()["UNISON"] = profileDir.absolutePath
                environment()["HOME"] = cacheDir.absolutePath
                environment()["TMPDIR"] = cacheDir.absolutePath
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("unison timed out:\n$output")
        }
        return RunResult(process.exitValue(), output)
    }

    private fun snapshot(root: File): Map<String, String> {
        val files = sortedMapOf<String, String>()
        root.walkTopDown()
            .filter { it.isFile }
            .forEach { files[it.relativeTo(root).path] = it.readText() }
        return files
    }

    private companion object {
        const val PROFILE = "e2e"
        const val SOCKET_PORT = 22334
    }
}
