package io.unisondroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.BinaryStatus
import io.unisondroid.app.sync.UnisonInfo
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
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(15, TimeUnit.SECONDS)
            assertTrue("${bundled.fileName} output was: $output", output.contains(bundled.version))
        }
    }
}
