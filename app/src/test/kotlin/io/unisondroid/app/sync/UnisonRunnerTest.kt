package io.unisondroid.app.sync

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

@Timeout(60)
class UnisonRunnerTest {

    @TempDir
    lateinit var tempDir: File

    private fun fakeUnison(name: String, script: String): File =
        File(tempDir, name).apply {
            writeText(script)
            setExecutable(true)
        }

    @Test
    fun `args and env reach the binary intact`() {
        val binary = fakeUnison(
            "dump-unison",
            """
            #!/bin/sh
            for a in "${'$'}@"; do
              printf '{"arg": "%s"}\n' "${'$'}a"
            done
            printf '{"env": "UNISON=%s"}\n' "${'$'}UNISON"
            """.trimIndent(),
        )

        val lines = runBlocking {
            UnisonRunner(binary).start(
                env = mapOf("UNISON" to "/x/unison"),
                args = listOf("/storage/emulated/0/My Folder/中文", "-server", "-fat"),
            ).output.toList()
        }

        assertEquals(
            listOf(
                """{"arg": "/storage/emulated/0/My Folder/中文"}""",
                """{"arg": "-server"}""",
                """{"arg": "-fat"}""",
                """{"env": "UNISON=/x/unison"}""",
            ),
            lines,
        )
    }

    @Test
    fun `output flow emits merged stdout and stderr lines until exit`() {
        val binary = fakeUnison(
            "noisy-unison",
            """
            #!/bin/sh
            echo line-one
            echo line-two-on-stderr >&2
            printf 'line-three-no-trailing-newline'
            """.trimIndent(),
        )

        runBlocking {
            val process = UnisonRunner(binary).start(env = emptyMap(), args = emptyList())

            assertEquals(
                listOf("line-one", "line-two-on-stderr", "line-three-no-trailing-newline"),
                process.output.toList(),
            )
            assertEquals(0, process.exitCode())
        }
    }

    @Test
    fun `kill produces nonzero exitCode`() {
        val binary = fakeUnison(
            "sleepy-unison",
            """
            #!/bin/sh
            sleep 60
            """.trimIndent(),
        )

        runBlocking {
            val process = UnisonRunner(binary).start(env = emptyMap(), args = emptyList())

            process.kill()

            val code = process.exitCode()
            assertTrue(code != 0, "expected nonzero exit code after kill, got $code")
        }
    }

    @Test
    fun `output flow completes after kill`() {
        val binary = fakeUnison(
            "chatty-sleepy-unison",
            """
            #!/bin/sh
            echo before-kill
            sleep 60
            """.trimIndent(),
        )

        runBlocking {
            val process = UnisonRunner(binary).start(env = emptyMap(), args = emptyList())
            val lines = mutableListOf<String>()

            withTimeout(10_000) {
                process.output.collect { line ->
                    lines += line
                    if (line == "before-kill") process.kill()
                }
            }

            assertEquals(listOf("before-kill"), lines)
            assertTrue(process.exitCode() != 0, "expected nonzero exit code after kill")
        }
    }

    @Test
    fun `nonexistent binary throws IOException at start`() {
        val runner = UnisonRunner(File(tempDir, "missing-unison"))

        assertThrows(IOException::class.java) {
            runner.start(env = emptyMap(), args = listOf("-version"))
        }
    }

    @Test
    fun `locate returns Available pointing at libunison so when present`() {
        val nativeDir = File(tempDir, "native").apply { mkdirs() }
        val lib = File(nativeDir, "libunison.so").apply { writeText("fake-so") }

        val status = BinaryLocator(nativeDir).locate()

        assertEquals(BinaryStatus.Available(lib), status)
    }

    @Test
    fun `locate returns Missing when libunison so is absent`() {
        val nativeDir = File(tempDir, "native").apply { mkdirs() }

        assertEquals(BinaryStatus.Missing, BinaryLocator(nativeDir).locate())
    }
}
