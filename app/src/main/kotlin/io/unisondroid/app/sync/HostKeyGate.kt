package io.unisondroid.app.sync

import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

sealed interface HostKeyOutcome {
    data object Trusted : HostKeyOutcome
    data object Untrusted : HostKeyOutcome
    data class Declined(val fingerprint: String) : HostKeyOutcome
    data class TimedOut(val fingerprint: String) : HostKeyOutcome
    data class ScanFailed(val detail: String) : HostKeyOutcome
}

/**
 * Trust-on-first-use for the server's host key, run before each sync. Changed
 * host keys are detected by ssh itself via StrictHostKeyChecking=yes against
 * the app's known_hosts, so this gate only handles first contact.
 */
class HostKeyGate(
    private val tool: SshTool,
    private val knownHostsFile: File,
) {

    suspend fun ensureTrusted(
        host: String,
        port: Int,
        interactive: Boolean,
        decisionTimeoutMs: Long,
        prompt: suspend (fingerprint: String) -> Boolean,
    ): HostKeyOutcome {
        if (tool.isHostKnown(host, port, knownHostsFile)) return HostKeyOutcome.Trusted
        // An unattended sync must never scan or prompt: it has no UI to answer,
        // so an unknown host is reported as untrusted for a later interactive run.
        if (!interactive) return HostKeyOutcome.Untrusted

        val entry = try {
            val scanned = tool.scanHostKeys(host, port)
            scanned.firstOrNull { it.keyType == "ssh-ed25519" } ?: scanned.firstOrNull()
                ?: return HostKeyOutcome.ScanFailed("no host keys returned")
        } catch (e: SshToolException) {
            return HostKeyOutcome.ScanFailed(e.message ?: "host key scan failed")
        }

        val approved = withTimeoutOrNull(decisionTimeoutMs) { prompt(entry.fingerprint) }
        return when (approved) {
            null -> HostKeyOutcome.TimedOut(entry.fingerprint)
            false -> HostKeyOutcome.Declined(entry.fingerprint)
            true -> {
                appendKnownHost(entry.knownHostsLine)
                HostKeyOutcome.Trusted
            }
        }
    }

    private fun appendKnownHost(line: String) {
        knownHostsFile.parentFile?.mkdirs()
        if (!knownHostsFile.exists()) {
            Files.createFile(
                knownHostsFile.toPath(),
                PosixFilePermissions.asFileAttribute(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                ),
            )
        }
        Files.write(
            knownHostsFile.toPath(),
            (line + "\n").toByteArray(),
            StandardOpenOption.APPEND,
        )
    }
}
