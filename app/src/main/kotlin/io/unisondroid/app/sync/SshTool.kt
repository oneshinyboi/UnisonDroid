package io.unisondroid.app.sync

import java.io.File
import java.util.concurrent.TimeUnit

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
                val fingerprintLine = pipeToFingerprint(line)
                // "<bits> SHA256:<b64> <type> (comment)"
                val parts = fingerprintLine.trim().split(" ")
                HostKeyEntry(
                    knownHostsLine = line,
                    keyType = parts.getOrNull(2) ?: line.split(" ").getOrNull(1) ?: "unknown",
                    fingerprint = parts.getOrNull(1) ?: "",
                )
            }
            .toList()
    }

    private fun pipeToFingerprint(knownHostsLine: String): String {
        val process = ProcessBuilder(listOf(keygen.absolutePath, "-lf", "-"))
            .start()
        process.outputStream.use { it.write(knownHostsLine.toByteArray()) }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        process.waitFor(PROCESS_TIMEOUT_S, TimeUnit.SECONDS)
        if (process.exitValue() != 0 || out.isBlank()) {
            throw SshToolException("ssh-keygen could not fingerprint scanned key: ${err.trim()}")
        }
        return out
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(PROCESS_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw SshToolException("timed out: ${command.first()}")
        }
        if (process.exitValue() != 0) {
            throw SshToolException("${command.first()} failed (exit ${process.exitValue()}): ${err.trim().ifEmpty { out.trim() }}")
        }
        return out
    }

    private companion object {
        const val KEY_PREFIX = "unisondroid-key-"
        const val PROCESS_TIMEOUT_S = 15L
    }
}
