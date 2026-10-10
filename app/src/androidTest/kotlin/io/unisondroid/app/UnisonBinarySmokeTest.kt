package io.unisondroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.BinaryStatus
import io.unisondroid.app.sync.UnisonInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UnisonBinarySmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyBundledUnisonBinaryRunsAndReportsItsVersion() {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        UnisonInfo.BUNDLED.forEach { bundled ->
            val status = BinaryLocator(nativeDir).locate(bundled.fileName)
            assertTrue("${bundled.fileName} must be present in $nativeDir", status is BinaryStatus.Available)
            val process = ProcessBuilder((status as BinaryStatus.Available).path.absolutePath, "-version")
                .redirectErrorStream(true)
                .apply {
                    // Unison aborts without HOME even for -version; the app supplies it
                    // for real runs (see LocalSyncE2eTest.runUnison).
                    environment()["HOME"] = context.cacheDir.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                }
                .start()
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().use { output.append(it.readText()) }
            }.apply { start() }
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(5_000)
            assertTrue("${bundled.fileName} did not exit; output was: $output", finished)
            assertEquals("${bundled.fileName} output was: $output", 0, process.exitValue())
            assertTrue("${bundled.fileName} output was: $output", output.toString().contains(bundled.version))
        }
    }
}
