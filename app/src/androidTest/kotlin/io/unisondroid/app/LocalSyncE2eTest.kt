package io.unisondroid.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.data.Profile
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.BinaryStatus
import io.unisondroid.app.sync.CONFLICT_REASONS
import io.unisondroid.app.sync.OutputParser
import io.unisondroid.app.sync.PrfGenerator
import io.unisondroid.app.sync.SshCommand
import io.unisondroid.app.sync.SyncSummary
import io.unisondroid.app.sync.UnisonInfo
import io.unisondroid.app.ui.hasAllFilesAccess
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
    fun app_declaresAllFilesAccess_andChecksItBeforeUsingExternalStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertTrue(
                "MANAGE_EXTERNAL_STORAGE must be declared for /storage/emulated/0 access",
                requested.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE),
            )
        } else {
            assertTrue(
                "WRITE_EXTERNAL_STORAGE must be declared for the API 26-29 fallback",
                requested.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
        // Exercise the production access check; its value is grant-dependent.
        hasAllFilesAccess(context)
    }

    @Test
    fun localSync_mirrorsChangesBothWays_andIsIdempotent() {
        val binary = locateBinary()
        val base = newBaseDir()
        val rootA = File(base, "a").apply { mkdirs() }
        val rootB = File(base, "b").apply { mkdirs() }
        val profileDir = File(base, "unison").apply { mkdirs() }
        val generated = writeGeneratedProfile(profileDir, PROFILE, rootA, rootB)
        assertFalse(
            "production profile must NOT disable unison's archive lock, got:\n$generated",
            generated.contains("ignorelocks"),
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

    @Test
    fun archiveLockIsHonored_andStaleLockBlocksThenClears() {
        val binary = locateBinary()
        val base = newBaseDir()
        val rootA = File(base, "a").apply { mkdirs() }
        val rootB = File(base, "b").apply { mkdirs() }
        val profileDir = File(base, "unison").apply { mkdirs() }
        writeGeneratedProfile(profileDir, PROFILE, rootA, rootB)
        File(rootA, "f.txt").writeText("x")

        val first = runUnison(binary, profileDir)
        assertEquals("initial sync failed:\n${first.output}", 0, first.exit)

        val archive = profileDir
            .listFiles { f -> f.isFile && f.name.startsWith("ar") && f.name.length == 34 }
            ?.firstOrNull()
            ?: error("expected an archive file ar<hash> in $profileDir, found: ${profileDir.list()?.toList()}")

        // A lock file left behind by a killed sync must block unison. This only
        // passes if the bundled binary honors O_EXCL archive locks on Android.
        val staleLock = File(profileDir, "lk" + archive.name.removePrefix("ar"))
        staleLock.writeText("stale")
        val blocked = runUnison(binary, profileDir)
        assertTrue(
            "an existing lock must stop unison, got exit=${blocked.exit}:\n${blocked.output}",
            blocked.exit != 0 && blocked.output.contains("should be deleted"),
        )

        // Clearing the stale lock (as the app does before each run) unblocks it.
        assertTrue("stale lock should be deletable", staleLock.delete())
        val after = runUnison(binary, profileDir)
        assertEquals(
            "sync must succeed once the stale lock is cleared:\n${after.output}",
            0,
            after.exit,
        )
    }

    @Test
    fun localSync_reportsConflict_thenResolvesTowardSecondRoot() {
        val binary = locateBinary()
        val base = newBaseDir()
        val rootA = File(base, "a").apply { mkdirs() }
        val rootB = File(base, "b").apply { mkdirs() }
        val profileDir = File(base, "unison").apply { mkdirs() }
        val generated = writeGeneratedProfile(profileDir, PROFILE, rootA, rootB)

        // The same relative path with different content in each replica. With no
        // archive Unison sees a conflict and, in -batch mode, skips it: both copies
        // are left in place and the conflict is reported on stdout.
        val conflictPath = "conflict.txt"
        val contentA = "content from A"
        val contentB = "content from B is longer"
        File(rootA, conflictPath).writeText(contentA)
        File(rootB, conflictPath).writeText(contentB)

        val conflictRun = runUnison(binary, profileDir)
        val conflicts = parseSummary(conflictRun.output).conflicts
        val conflict = conflicts.singleOrNull { it.path == conflictPath }
            ?: error(
                "expected a conflict record for '$conflictPath', got $conflicts " +
                    "(exit=${conflictRun.exit})\n${conflictRun.output}",
            )
        assertTrue(
            "the reason must be one Unison reports for a real conflict, got '${conflict.reason}'",
            conflict.reason in CONFLICT_REASONS,
        )
        assertTrue("a real conflict must be resolvable", conflict.resolvable)
        // The per-side metadata is parsed from the display block Unison prints
        // before the `skipped:` line. Sizes are order-independent here.
        assertEquals(
            "both sides' sizes must parse from the display block",
            setOf(contentA.length.toLong(), contentB.length.toLong()),
            setOf(conflict.local?.sizeBytes, conflict.remote?.sizeBytes),
        )
        assertEquals(contentA, File(rootA, conflictPath).readText())
        assertEquals(contentB, File(rootB, conflictPath).readText())

        // Resolve the conflict toward the second root with a scoped preferpartial
        // preference (the same shape ConflictResolver emits for KEEP_REMOTE).
        File(profileDir, "$PROFILE.prf").writeText(
            generated + "preferpartial = Path $conflictPath -> ${rootB.absolutePath}\n",
        )

        val resolveRun = runUnison(binary, profileDir)
        assertEquals("resolve run failed:\n${resolveRun.output}", 0, resolveRun.exit)
        assertEquals(
            "rootA must take rootB's content once the conflict is resolved",
            contentB,
            File(rootA, conflictPath).readText(),
        )
        assertEquals("rootB must keep its content", contentB, File(rootB, conflictPath).readText())
        val remaining = parseSummary(resolveRun.output).conflicts
        assertTrue(
            "the resolved path must no longer be a conflict, got $remaining",
            remaining.none { it.path == conflictPath },
        )
    }

    @Test
    fun localSync_worksWithEveryBundledUnisonVersion() {
        val nativeDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir,
        )
        UnisonInfo.BUNDLED.forEach { bundled ->
            val status = BinaryLocator(nativeDir).locate(bundled.fileName)
            assertTrue("${bundled.fileName} must be present", status is BinaryStatus.Available)
            val binary = (status as BinaryStatus.Available).path

            val base = newBaseDir()
            val rootA = File(base, "a").apply { mkdirs() }
            val rootB = File(base, "b").apply { mkdirs() }
            val profileDir = File(base, "unison").apply { mkdirs() }
            writeGeneratedProfile(profileDir, PROFILE, rootA, rootB)
            File(rootA, "hello.txt").writeText("hello ${bundled.version}")

            val run = runUnison(binary, profileDir)
            assertEquals("${bundled.version} sync failed:\n${run.output}", 0, run.exit)
            val summary = parseSummary(run.output)
            assertTrue(
                "${bundled.version} must transfer the new file; got:\n${run.output}",
                summary.transferred >= 1,
            )
            assertEquals("hello ${bundled.version}", File(rootB, "hello.txt").readText())
        }
    }

    private fun locateBinary(): File {
        val nativeDir = File(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.nativeLibraryDir)
        val status = BinaryLocator(nativeDir).locate(UnisonInfo.DEFAULT.fileName)
        assertTrue("${UnisonInfo.DEFAULT.fileName} must be present in $nativeDir", status is BinaryStatus.Available)
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
        val generated = PrfGenerator.generate(
            profile,
            SshCommand(
                binary = File("/nonexistent/libssh.so"),
                configFile = File("/nonexistent/ssh_config"),
            ),
        )
        // Replace the ssh root line with the second local root, to test a
        // local-local sync without a server.
        val localLocal = generated.replace(
            Regex("""root = ssh://e2e@localhost\S*"""),
            "root = ${rootB.absolutePath}",
        )
        check(localLocal != generated) { "PrfGenerator no longer emits the expected ssh root" }
        check(!localLocal.contains("ssh://")) { "ssh root must have been replaced, got:\n$localLocal" }
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

    private fun parseSummary(output: String): SyncSummary {
        val parser = OutputParser()
        parser.feed(output)
        return parser.finalize(0)
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
    }
}
