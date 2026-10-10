package io.unisondroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.unisondroid.app.sync.BinaryLocator
import io.unisondroid.app.sync.BinaryStatus
import io.unisondroid.app.sync.UnisonInfo
import io.unisondroid.app.sync.UnisonRunner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UnisonRunnerE2eTest {

    @Test
    fun localSyncThroughTheAppRunnerCompletes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val status = BinaryLocator(nativeDir).locate(UnisonInfo.DEFAULT.fileName)
        assertTrue("${UnisonInfo.DEFAULT.fileName} must be present", status is BinaryStatus.Available)
        val unison = (status as BinaryStatus.Available).path

        val base = File(context.cacheDir, "runner-e2e-${System.nanoTime()}").apply { mkdirs() }
        val a = File(base, "a").apply { mkdirs() }
        val b = File(base, "b").apply { mkdirs() }
        val u = File(base, "u").apply { mkdirs() }
        File(u, "p.prf").writeText(
            "root = ${a.absolutePath}\nroot = ${b.absolutePath}\nbatch = true\n",
        )
        File(a, "hello.txt").writeText("hi")

        val process = UnisonRunner(unison, context.cacheDir).start(
            env = mapOf("UNISON" to u.absolutePath, "HOME" to base.absolutePath),
            args = listOf("p", "-batch"),
        )
        val lines = runBlocking { process.output.toList() }
        val exit = runBlocking { process.exitCode() }

        assertEquals("output was:\n${lines.joinToString("\n")}", 0, exit)
        assertTrue(
            "the run must complete; got:\n${lines.joinToString("\n")}",
            lines.any { it.contains("Synchronization complete") },
        )
        assertEquals("hi", File(b, "hello.txt").readText())
    }
}
