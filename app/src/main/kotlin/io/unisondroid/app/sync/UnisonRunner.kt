package io.unisondroid.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class BinaryLocator(private val nativeLibraryDir: File) {
    fun locate(fileName: String): BinaryStatus {
        val candidate = File(nativeLibraryDir, fileName)
        return if (candidate.isFile) BinaryStatus.Available(candidate) else BinaryStatus.Missing
    }

    fun locateSsh(): BinaryStatus {
        val candidate = File(nativeLibraryDir, "libssh.so")
        return if (candidate.isFile) BinaryStatus.Available(candidate) else BinaryStatus.Missing
    }
}

sealed interface BinaryStatus {
    data class Available(val path: File) : BinaryStatus
    data object Missing : BinaryStatus
}

open class UnisonRunner(
    private val binary: File,
    private val scratchDir: File = File(System.getProperty("java.io.tmpdir") ?: "."),
) {

    open fun start(env: Map<String, String>, args: List<String>): RunningProcess {
        // Unison switches its own stdout to non-blocking (it drives the remote transport
        // through Lwt), and a full stdout pipe then surfaces as OCaml's fatal
        // "Sys_blocked_io" instead of applying backpressure. A regular file can never
        // return EAGAIN, so capture stdout+stderr there and tail the file instead of
        // reading a pipe.
        val logFile = File.createTempFile("unisondroid-unison-", ".log", scratchDir)
        val process = try {
            ProcessBuilder(listOf(binary.absolutePath) + args)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .apply { environment().putAll(env) }
                .start()
        } catch (e: IOException) {
            logFile.delete()
            throw e
        }
        return RunningProcess(output = tail(logFile, process), process = process, logFile = logFile)
    }

    private fun tail(file: File, process: Process): Flow<String> = flow {
        val pending = ByteArrayOutputStream()
        var offset = 0L
        var skipLf = false

        suspend fun emitLine() {
            emit(pending.toByteArray().toString(Charsets.UTF_8))
            pending.reset()
        }

        suspend fun readAvailable() {
            if (!file.exists() || offset > file.length()) return
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    offset += read
                    var i = 0
                    if (skipLf && buffer[0] == LF) i = 1
                    skipLf = false
                    while (i < read) {
                        val b = buffer[i]
                        when (b) {
                            LF -> emitLine()
                            CR -> {
                                emitLine()
                                if (i + 1 < read) {
                                    if (buffer[i + 1] == LF) i++
                                } else {
                                    skipLf = true
                                }
                            }
                            else -> pending.write(b.toInt())
                        }
                        i++
                    }
                }
            }
        }

        try {
            while (true) {
                readAvailable()
                if (!process.isAlive) {
                    // Catch anything flushed between the read above and process exit.
                    readAvailable()
                    if (pending.size() > 0) emitLine()
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            file.delete()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val POLL_INTERVAL_MS = 100L
        const val READ_BUFFER_BYTES = 8192
        val LF = '\n'.code.toByte()
        val CR = '\r'.code.toByte()
    }
}

open class RunningProcess(
    open val output: Flow<String>,
    private val process: Process? = null,
    private val logFile: File? = null,
) {
    open suspend fun exitCode(): Int = withContext(Dispatchers.IO) {
        checkNotNull(process) { "exitCode requires a real process" }.waitFor()
    }

    open fun kill() {
        val current = process ?: return
        killDescendantsBestEffort(current)
        current.destroyForcibly()
        logFile?.delete()
    }

    private fun killDescendantsBestEffort(current: Process) {
        runCatching {
            val handle = Process::class.java.getMethod("toHandle").invoke(current) as ProcessHandle
            handle.descendants().forEach { it.destroyForcibly() }
        }
    }
}
