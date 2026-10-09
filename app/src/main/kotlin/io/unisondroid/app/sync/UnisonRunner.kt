package io.unisondroid.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

class BinaryLocator(private val nativeLibraryDir: File) {
    fun locate(): BinaryStatus {
        val candidate = File(nativeLibraryDir, "libunison.so")
        return if (candidate.isFile) BinaryStatus.Available(candidate) else BinaryStatus.Missing
    }
}

sealed interface BinaryStatus {
    data class Available(val path: File) : BinaryStatus
    data object Missing : BinaryStatus
}

open class UnisonRunner(private val binary: File) {

    open fun start(env: Map<String, String>, args: List<String>): RunningProcess {
        val process = ProcessBuilder(listOf(binary.absolutePath) + args)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()
        val output = flow {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    emit(reader.readLine() ?: break)
                }
            }
        }.flowOn(Dispatchers.IO)
        return RunningProcess(output, handleOf(process), process)
    }

    private fun handleOf(process: Process): ProcessHandle =
        ProcessHandle.of(pidOf(process)).orElseThrow()

    private fun pidOf(process: Process): Long =
        Process::class.java.getMethod("pid").invoke(process) as Long
}

open class RunningProcess(
    open val output: Flow<String>,
    private val handle: ProcessHandle,
    private val process: Process? = null,
) {
    open suspend fun exitCode(): Int = withContext(Dispatchers.IO) {
        checkNotNull(process) { "exitCode requires a real process" }.waitFor()
    }

    open fun kill() {
        handle.descendants().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
    }
}
