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
import java.nio.file.StandardCopyOption
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

/** Whether a sync may prompt the user for an unknown host key. */
enum class SyncMode { INTERACTIVE, UNATTENDED }

/** Outcome of a sync request, so a worker can distinguish results without parsing state. */
enum class SyncOutcome { COMPLETED, FAILED, SKIPPED_UNTRUSTED, BUSY }

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
    // Reports whether the local root lives on a removable volume (SD card),
    // which is normally case-insensitive FAT/exFAT. Injected so the engine
    // stays Android-free and tests can stub it.
    private val removableVolume: (String) -> Boolean = { false },
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    open val state: StateFlow<SyncState> = _state.asStateFlow()

    private val syncMutex = Mutex()

    @Volatile
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    @Volatile
    private var activeJob: Job? = null

    open suspend fun requestSync(
        profileId: String,
        mode: SyncMode,
        variant: SyncVariant = SyncVariant.TWO_WAY,
    ): SyncOutcome {
        if (!syncMutex.tryLock()) return SyncOutcome.BUSY
        try {
            val (binary, sshBinary) = locateBinaries(profileId) ?: return SyncOutcome.FAILED
            return runSync(profileId, binary, sshBinary, mode, variant = variant)
        } finally {
            syncMutex.unlock()
        }
    }

    /**
     * Runs a scoped resolve pass: the profile's normal policy prefs plus the
     * per-path decisions from [ConflictResolver.resolutionPreferences]. The run
     * reuses the same host-key gate / process / cancel machinery as [requestSync]
     * and rewrites `lastConflicts` from the fresh output, so resolved paths drop
     * out and unresolved ones remain.
     */
    open suspend fun resolveConflicts(profileId: String, decisions: Map<String, Resolution>): SyncOutcome {
        if (!syncMutex.tryLock()) return SyncOutcome.BUSY
        try {
            val (binary, sshBinary) = locateBinaries(profileId) ?: return SyncOutcome.FAILED
            val profile = profiles.get(profileId)
            val extraPrefs =
                if (profile == null) emptyList() else ConflictResolver.resolutionPreferences(profile, decisions)
            return runSync(profileId, binary, sshBinary, SyncMode.INTERACTIVE, extraPrefs)
        } finally {
            syncMutex.unlock()
        }
    }

    private fun locateBinaries(profileId: String): Pair<File, File>? {
        val binary = when (val status = binaryLocator.locate()) {
            is BinaryStatus.Missing -> {
                _state.value = SyncState.Failed(
                    profileId = profileId,
                    reason = SyncState.Reason.BINARY_MISSING,
                    detail = "libunison.so not found in the native library directory",
                )
                return null
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
                return null
            }
            is BinaryStatus.Available -> status.path
        }
        return binary to sshBinary
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

    private suspend fun runSync(
        profileId: String,
        binary: File,
        sshBinary: File,
        mode: SyncMode,
        extraPrefs: List<String> = emptyList(),
        variant: SyncVariant = SyncVariant.TWO_WAY,
    ): SyncOutcome {
        activeJob = currentCoroutineContext()[Job]
        // Diagnostics connect and exit without syncing, so they must not touch the
        // profile's recorded last-sync state (a green "OK" would imply a sync ran).
        val recordResult = !variant.isDiagnostic
        clearStaleLocks()
        var profile: Profile? = null
        var process: RunningProcess? = null
        var keyFile: File? = null
        var success = false
        try {
            val p = profiles.get(profileId) ?: run {
                _state.value = SyncState.Failed(profileId, SyncState.Reason.UNKNOWN, "Profile not found: $profileId")
                return SyncOutcome.FAILED
            }
            profile = p
            _state.value = SyncState.Connecting(profileId)

            val knownHosts = File(sshHome, "known_hosts")
            val outcome = HostKeyGate(sshTool, knownHosts)
                .ensureTrusted(
                    host = p.host,
                    port = p.sshPort,
                    interactive = mode == SyncMode.INTERACTIVE,
                    decisionTimeoutMs = hostKeyDecisionTimeoutMs,
                ) { fingerprint ->
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
                is HostKeyOutcome.Untrusted -> {
                    // Unattended syncs must never prompt; surface a Failed state so
                    // the user sees why, but leave the profile's lastResult untouched.
                    _state.value = SyncState.Failed(profileId, SyncState.Reason.AUTH, hostKeyNeedsApprovalDetail(p))
                    return SyncOutcome.SKIPPED_UNTRUSTED
                }
                is HostKeyOutcome.Declined -> {
                    failSync(profileId, p, SyncState.Reason.AUTH, hostKeyDeclinedDetail(p), recordResult)
                    return SyncOutcome.FAILED
                }
                is HostKeyOutcome.TimedOut -> {
                    failSync(profileId, p, SyncState.Reason.TUNNEL, hostKeyTimeoutDetail(p), recordResult)
                    return SyncOutcome.FAILED
                }
                is HostKeyOutcome.ScanFailed -> {
                    failSync(
                        profileId,
                        p,
                        SyncState.Reason.TUNNEL,
                        "Could not verify the host key: ${outcome.detail}",
                        recordResult,
                    )
                    return SyncOutcome.FAILED
                }
            }

            val key = writePrivateKey(p)
            keyFile = key
            unisonDir.mkdirs()
            val configFile = File(sshHome, "ssh_config")
            writeAtomically(configFile, SshConfig.render(key, knownHosts, p.sshPort))
            val sshCommand = SshCommand(binary = sshBinary, configFile = configFile)
            val caseInsensitive = removableVolume(p.localRoot)
            File(unisonDir, "${p.id}.prf")
                .writeText(
                    PrfGenerator.generate(
                        profile = p,
                        ssh = sshCommand,
                        extraPrefs = variant.preferences(p) + extraPrefs,
                        caseInsensitive = caseInsensitive,
                        includeConflictPolicy = !variant.isOneWay,
                    ),
                )

            val proc = runnerFactory(binary).start(
                env = mapOf("UNISON" to unisonDir.absolutePath),
                args = listOf(p.id, "-batch") + variant.args(),
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
                versionMismatch != null -> {
                    failSync(profileId, p, SyncState.Reason.VERSION, versionMismatch!!, recordResult)
                    return SyncOutcome.FAILED
                }
                hostKeyFailure != null -> {
                    failSync(profileId, p, SyncState.Reason.TUNNEL, hostKeyFailure!!, recordResult)
                    return SyncOutcome.FAILED
                }
                authFailure != null -> {
                    failSync(profileId, p, SyncState.Reason.AUTH, authFailure!!, recordResult)
                    return SyncOutcome.FAILED
                }
                lostConnection != null -> {
                    failSync(profileId, p, SyncState.Reason.TUNNEL, lostConnection!!, recordResult)
                    return SyncOutcome.FAILED
                }
                permissionDenied != null -> {
                    failSync(profileId, p, SyncState.Reason.LOCAL_PERMISSIONS, permissionDenied!!, recordResult)
                    return SyncOutcome.FAILED
                }
                // Exit codes (uicommon.ml): 0 = okay, 1 = some items skipped
                // (conflicts/problems, no failure), 2 = some non-fatal failure,
                // 3 = fatal. Skipping a conflict is normal, so 0-2 are all a
                // completed run; the summary decides OK/WARNINGS/FAILED.
                exit >= FATAL_EXIT_CODE -> {
                    failSync(profileId, p, SyncState.Reason.EXIT, logTail(log), recordResult)
                    return SyncOutcome.FAILED
                }
                else -> {
                    // A completed run (exit 0-2, no fatal marker) is never OK when
                    // the process reported a non-zero exit: it at least warned. A
                    // partial transfer is surfaced in `failed` for display but is not
                    // a hard failure — Unison folds `anyPartial` into the exit-1
                    // (skippy) path — so it classifies as WARNINGS, not FAILED.
                    val hasHardFailure = summary.failed.any { it.message != PARTIAL_TRANSFER_MESSAGE }
                    val result = when {
                        hasHardFailure -> SyncResult.FAILED
                        summary.conflicts.isNotEmpty() -> SyncResult.WARNINGS
                        summary.failed.isNotEmpty() || exit != 0 -> SyncResult.WARNINGS
                        else -> SyncResult.OK
                    }
                    if (recordResult) {
                        profiles.save(
                            p.copy(
                                lastSyncedAt = clock.millis(),
                                lastResult = result,
                                lastConflicts = summary.conflicts,
                            ),
                        )
                    }
                    success = true
                    _state.value = SyncState.Finished(profileId, summary)
                    return SyncOutcome.COMPLETED
                }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                profile?.let { markFailed(it, recordResult) }
                _state.value = SyncState.Failed(profileId, SyncState.Reason.CANCELLED, "Sync cancelled before completion")
            }
            throw ce
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                failSync(profileId, profile, mapException(t), failureDetail(t), recordResult)
            }
            return SyncOutcome.FAILED
        } finally {
            if (!success) process?.kill()
            keyFile?.delete()
            activeJob = null
        }
    }

    // Writes the ssh config via a temp file and an atomic rename so a crash mid-write cannot
    // leave a truncated config behind for the next run.
    private fun writeAtomically(target: File, contents: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(contents)
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
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

    private suspend fun failSync(
        profileId: String,
        profile: Profile?,
        reason: SyncState.Reason,
        detail: String,
        record: Boolean = true,
    ) {
        profile?.let { markFailed(it, record) }
        _state.value = SyncState.Failed(profileId, reason, detail)
    }

    private suspend fun markFailed(profile: Profile, record: Boolean = true) {
        if (record) profiles.save(profile.copy(lastResult = SyncResult.FAILED))
    }

    private fun logTail(log: List<String>): String = log.takeLast(DETAIL_TAIL_LINES).joinToString(LINE_FEED)

    private fun mapException(t: Throwable): SyncState.Reason = SyncState.Reason.UNKNOWN

    private fun failureDetail(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private fun hostKeyDeclinedDetail(profile: Profile): String =
        "Host key for ${profile.host}:${profile.sshPort} was declined by the user"

    private fun hostKeyTimeoutDetail(profile: Profile): String =
        "Host key approval for ${profile.host}:${profile.sshPort} timed out; no decision was made"

    private fun hostKeyNeedsApprovalDetail(profile: Profile): String =
        "Host key for ${profile.host}:${profile.sshPort} needs approval; " +
            "open the app and approve it before this profile can sync in the background"

    private companion object {
        const val LOG_MAX_LINES = 2000
        const val DETAIL_TAIL_LINES = 20
        const val HOST_KEY_DECISION_TIMEOUT_MS = 300_000L
        const val LINE_FEED = "\n"
        const val FATAL_EXIT_CODE = 3
        const val AUTH_MARKER = "Permission denied (publickey"
        const val HOST_KEY_MARKER = "Host key verification failed"
        const val LOST_CONNECTION_MARKER = "Lost connection with the server"
        const val PERMISSION_DENIED_MARKER = "Permission denied"
        val STALE_LOCK = Regex("lk[0-9a-f]{32}")
    }
}
