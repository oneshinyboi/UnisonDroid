package io.unisondroid.app.sync

import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock

sealed interface SyncState {
    data object Idle : SyncState
    data class AwaitingHostKey(val profileId: String, val fingerprint: String) : SyncState
    data class Connecting(val profileId: String) : SyncState
    data class Syncing(val profileId: String, val log: List<String>, val progress: Float) : SyncState
    data class Finished(val profileId: String, val summary: SyncSummary) : SyncState
    data class Failed(val profileId: String, val reason: Reason, val detail: String) : SyncState

    enum class Reason {
        BINARY_MISSING,
        AUTH,
        TUNNEL,
        VERSION,
        LOCAL_PERMISSIONS,
        CANCELLED,
        EXIT,
        UNKNOWN,
    }
}

open class SyncEngine(
    private val binaryLocator: BinaryLocator,
    private val profiles: ProfileRepository,
    private val keys: KeyVault,
    private val sshTool: SshTool,
    private val runnerFactory: (File) -> UnisonRunner,
    private val parserFactory: () -> OutputParser,
    private val unisonDir: File,
    private val sshHome: File,
    private val clock: Clock,
    private val hostKeyDecisionTimeoutMs: Long = HOST_KEY_DECISION_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    open val state: StateFlow<SyncState> = _state.asStateFlow()

    private val syncMutex = Mutex()

    @Volatile
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    @Volatile
    private var activeJob: Job? = null

    open suspend fun requestSync(profileId: String): Boolean {
        val binary = when (val status = binaryLocator.locate()) {
            is BinaryStatus.Missing -> {
                _state.value = SyncState.Failed(
                    profileId = profileId,
                    reason = SyncState.Reason.BINARY_MISSING,
                    detail = "libunison.so not found in the native library directory",
                )
                return true
            }
            is BinaryStatus.Available -> status.path
        }
        val sshBinary = when (val status = binaryLocator.locateSsh()) {
            is BinaryStatus.Missing -> {
                _state.value = SyncState.Failed(
                    profileId = profileId,
                    reason = SyncState.Reason.BINARY_MISSING,
                    detail = "libssh.so not found in the native library directory",
                )
                return true
            }
            is BinaryStatus.Available -> status.path
        }
        if (!syncMutex.tryLock()) return false
        try {
            runSync(profileId, binary, sshBinary)
        } finally {
            syncMutex.unlock()
        }
        return true
    }

    open suspend fun respondHostKey(approve: Boolean) {
        pendingDecision?.complete(approve)
    }

    open fun cancel() {
        activeJob?.cancel()
    }

    // The bundled unison locks each archive via an O_EXCL file named
    // "lk<32-hex-archive-hash>" in the UNISON dir (see src/update.ml archiveName
    // / src/lock.ml). A sync killed mid-run leaves that file behind, and unison
    // then refuses the next run until it is removed. This dir is app-private and
    // syncs are serialized (mutex held here), so any such file is stale.
    private fun clearStaleLocks() {
        if (!unisonDir.isDirectory) return
        unisonDir.listFiles { f -> f.isFile && STALE_LOCK.matches(f.name) }?.forEach { it.delete() }
    }

    private suspend fun runSync(profileId: String, binary: File, sshBinary: File) {
        activeJob = currentCoroutineContext()[Job]
        clearStaleLocks()
        var profile: Profile? = null
        var process: RunningProcess? = null
        var keyFile: File? = null
        var success = false
        try {
            val p = profiles.get(profileId) ?: run {
                _state.value = SyncState.Failed(profileId, SyncState.Reason.UNKNOWN, "Profile not found: $profileId")
                return
            }
            profile = p
            _state.value = SyncState.Connecting(profileId)

            val knownHosts = File(sshHome, "known_hosts")
            val outcome = HostKeyGate(sshTool, knownHosts)
                .ensureTrusted(p.host, p.sshPort, hostKeyDecisionTimeoutMs) { fingerprint ->
                    val pending = CompletableDeferred<Boolean>()
                    pendingDecision = pending
                    _state.value = SyncState.AwaitingHostKey(profileId, fingerprint)
                    try {
                        pending.await()
                    } finally {
                        pendingDecision = null
                    }
                }
            when (outcome) {
                is HostKeyOutcome.Trusted -> Unit
                is HostKeyOutcome.Declined -> {
                    failSync(profileId, p, SyncState.Reason.AUTH, hostKeyDeclinedDetail(p))
                    return
                }
                is HostKeyOutcome.TimedOut -> {
                    failSync(profileId, p, SyncState.Reason.TUNNEL, hostKeyTimeoutDetail(p))
                    return
                }
                is HostKeyOutcome.ScanFailed -> {
                    failSync(profileId, p, SyncState.Reason.TUNNEL, "Could not scan host keys: ${outcome.detail}")
                    return
                }
            }

            val key = writePrivateKey(p)
            keyFile = key
            unisonDir.mkdirs()
            val sshCommand = SshCommand(
                binary = sshBinary,
                keyFile = key,
                knownHosts = knownHosts,
                port = p.sshPort,
            )
            File(unisonDir, "${p.id}.prf").writeText(PrfGenerator.generate(p, sshCommand))

            val proc = runnerFactory(binary).start(
                env = mapOf("UNISON" to unisonDir.absolutePath),
                args = listOf(p.id, "-batch"),
            )
            process = proc

            val parser = parserFactory()
            val log = ArrayDeque<String>()
            var progress = 0f
            var versionMismatch: String? = null
            var authFailure: String? = null
            var hostKeyFailure: String? = null
            var lostConnection: String? = null
            var permissionDenied: String? = null

            val exit = coroutineScope {
                val collector = launch {
                    proc.output.collect { line ->
                        if (log.size >= LOG_MAX_LINES) log.removeFirst()
                        log.addLast(line)
                        for (event in parser.feed(line + LINE_FEED)) {
                            when (event) {
                                is SyncEvent.Progress -> progress = event.fraction
                                is SyncEvent.VersionMismatch -> if (versionMismatch == null) versionMismatch = event.detail
                                else -> Unit
                            }
                        }
                        if (authFailure == null && line.contains(AUTH_MARKER)) authFailure = line
                        if (hostKeyFailure == null && line.contains(HOST_KEY_MARKER)) hostKeyFailure = line
                        if (lostConnection == null && line.contains(LOST_CONNECTION_MARKER)) lostConnection = line
                        if (permissionDenied == null && line.contains(PERMISSION_DENIED_MARKER)) permissionDenied = line
                        _state.value = SyncState.Syncing(profileId, log.toList(), progress)
                    }
                }
                collector.join()
                proc.exitCode()
            }

            val summary = parser.finalize(exit)
            when {
                versionMismatch != null -> failSync(profileId, p, SyncState.Reason.VERSION, versionMismatch!!)
                hostKeyFailure != null -> failSync(profileId, p, SyncState.Reason.TUNNEL, hostKeyFailure!!)
                authFailure != null -> failSync(profileId, p, SyncState.Reason.AUTH, authFailure!!)
                lostConnection != null -> failSync(profileId, p, SyncState.Reason.TUNNEL, lostConnection!!)
                permissionDenied != null -> failSync(profileId, p, SyncState.Reason.LOCAL_PERMISSIONS, permissionDenied!!)
                exit != 0 -> failSync(profileId, p, SyncState.Reason.EXIT, logTail(log))
                else -> {
                    val result = if (summary.failed == 0 && summary.conflicts == 0) SyncResult.OK else SyncResult.WARNINGS
                    profiles.save(p.copy(lastSyncedAt = clock.millis(), lastResult = result))
                    success = true
                    _state.value = SyncState.Finished(profileId, summary)
                }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                profile?.let { markFailed(it) }
                _state.value = SyncState.Failed(profileId, SyncState.Reason.CANCELLED, "Sync cancelled before completion")
            }
            throw ce
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                failSync(profileId, profile, mapException(t), failureDetail(t))
            }
        } finally {
            if (!success) process?.kill()
            keyFile?.delete()
            activeJob = null
        }
    }

    private suspend fun writePrivateKey(profile: Profile): File {
        val pem = keys.privateKeyPem(profile.sshKeyId)
        sshHome.mkdirs()
        val keyFile = File(sshHome, profile.sshKeyId)
        Files.deleteIfExists(keyFile.toPath())
        Files.createFile(
            keyFile.toPath(),
            PosixFilePermissions.asFileAttribute(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            ),
        )
        Files.write(keyFile.toPath(), pem.toByteArray())
        return keyFile
    }

    private suspend fun failSync(profileId: String, profile: Profile?, reason: SyncState.Reason, detail: String) {
        profile?.let { markFailed(it) }
        _state.value = SyncState.Failed(profileId, reason, detail)
    }

    private suspend fun markFailed(profile: Profile) {
        profiles.save(profile.copy(lastResult = SyncResult.FAILED))
    }

    private fun logTail(log: List<String>): String = log.takeLast(DETAIL_TAIL_LINES).joinToString(LINE_FEED)

    private fun mapException(t: Throwable): SyncState.Reason = SyncState.Reason.UNKNOWN

    private fun failureDetail(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private fun hostKeyDeclinedDetail(profile: Profile): String =
        "Host key for ${profile.host}:${profile.sshPort} was declined by the user"

    private fun hostKeyTimeoutDetail(profile: Profile): String =
        "Host key approval for ${profile.host}:${profile.sshPort} timed out; no decision was made"

    private companion object {
        const val LOG_MAX_LINES = 2000
        const val DETAIL_TAIL_LINES = 20
        const val HOST_KEY_DECISION_TIMEOUT_MS = 300_000L
        const val LINE_FEED = "\n"
        const val AUTH_MARKER = "Permission denied (publickey"
        const val HOST_KEY_MARKER = "Host key verification failed"
        const val LOST_CONNECTION_MARKER = "Lost connection with the server"
        const val PERMISSION_DENIED_MARKER = "Permission denied"
        val STALE_LOCK = Regex("lk[0-9a-f]{32}")
    }
}
