package io.unisondroid.app.sync

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class GeneratedKey(
    val privatePem: String,
    val publicKeyLine: String,
)

data class HostKeyEntry(
    val knownHostsLine: String,
    val keyType: String,
    val fingerprint: String,
)

class SshToolException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface SshTool {
    fun generateKey(comment: String): GeneratedKey

    fun derivePublicKey(pem: String): String

    fun scanHostKeys(host: String, port: Int): List<HostKeyEntry>
}

/**
 * Shells out to the OpenSSH client tools bundled as native executables; every
 * path they use is passed explicitly, so they never consult $HOME, /etc/ssh,
 * or the passwd database.
 */
class ProcessSshTool(
    private val keygen: File,
    private val keyscan: File,
    private val workDir: File,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : SshTool {

    override fun generateKey(comment: String): GeneratedKey {
        workDir.mkdirs()
        val tmp = File.createTempFile(KEY_PREFIX, null, workDir)
        try {
            tmp.delete()
            run(listOf(keygen.absolutePath, "-t", "ed25519", "-N", "", "-C", comment, "-f", tmp.absolutePath))
            val pub = File("${tmp.absolutePath}.pub")
            val pubLine = pub.readText().trim()
            val withComment = if (pubLine.count { it == ' ' } >= 2) pubLine else "$pubLine $comment"
            return GeneratedKey(privatePem = tmp.readText(), publicKeyLine = withComment)
        } finally {
            tmp.delete()
            File("${tmp.absolutePath}.pub").delete()
        }
    }

    override fun derivePublicKey(pem: String): String {
        workDir.mkdirs()
        val tmp = File.createTempFile(KEY_PREFIX, null, workDir)
        try {
            tmp.writeText(pem)
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            val derived = run(listOf(keygen.absolutePath, "-y", "-f", tmp.absolutePath)).trim()
            if (derived.isEmpty()) throw SshToolException("keygen derived an empty public key")
            return derived
        } finally {
            tmp.delete()
        }
    }

    override fun scanHostKeys(host: String, port: Int): List<HostKeyEntry> {
        val output = run(
            listOf(
                keyscan.absolutePath, "-T", "5",
                "-t", "ed25519,ecdsa,rsa",
                "-p", port.toString(), host,
            ),
        )
        return output.lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                HostKeyEntry(
                    knownHostsLine = line,
                    // The line is "[host]:port <key-algo> <blob> [comment]".
                    keyType = line.split(" ").getOrNull(1) ?: "unknown",
                    fingerprint = fingerprint(line),
                )
            }
            .toList()
    }

    private fun fingerprint(knownHostsLine: String): String {
        val process = ProcessBuilder(listOf(keygen.absolutePath, "-lf", "-")).start()
        process.outputStream.use { it.write((knownHostsLine + "\n").toByteArray()) }
        val drained = drain(process, "ssh-keygen", "fingerprinting the scanned key")
        if (process.exitValue() != 0 || drained.out.isBlank()) {
            throw SshToolException("ssh-keygen could not fingerprint the scanned key: ${drained.err.trim()}")
        }
        // "<bits> SHA256:<b64> <comment> (<type>)"
        return drained.out.trim().split(" ").getOrNull(1) ?: ""
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(File(DEV_NULL)))
            .start()
        val drained = drain(process, command.first(), "running ${command.drop(1).joinToString(" ")}")
        if (process.exitValue() != 0) {
            throw SshToolException(
                "${command.first()} failed (exit ${process.exitValue()}): ${drained.err.trim().ifEmpty { drained.out.trim() }}",
            )
        }
        return drained.out
    }

    private data class Drained(val out: String, val err: String)

    /**
     * Drains stdout and stderr concurrently (so a chatty child cannot fill a
     * pipe buffer and deadlock) and kills the child if it outlives the timeout
     * (so a passphrase prompt or a stuck tool cannot hang the caller).
     */
    private fun drain(process: Process, tool: String, action: String): Drained {
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val outThread = thread { process.inputStream.drainInto(outBuf) }
        val errThread = thread { process.errorStream.drainInto(errBuf) }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(THREAD_JOIN_MS)
            errThread.join(THREAD_JOIN_MS)
            throw SshToolException("$tool timed out $action")
        }
        outThread.join(THREAD_JOIN_MS)
        errThread.join(THREAD_JOIN_MS)
        return Drained(outBuf.toString(), errBuf.toString())
    }

    private fun InputStream.drainInto(target: StringBuilder) {
        runCatching {
            bufferedReader().use { reader ->
                reader.forEachLine { target.append(it).append('\n') }
            }
        }
    }

    private companion object {
        const val KEY_PREFIX = "unisondroid-key-"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val THREAD_JOIN_MS = 2_000L
        const val DEV_NULL = "/dev/null"
    }
}
