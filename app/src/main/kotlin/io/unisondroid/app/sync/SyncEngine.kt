package io.unisondroid.app.sync

import io.unisondroid.app.data.HostKeyStore
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.SyncResult
import io.unisondroid.app.data.TofuVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.File
import java.net.ConnectException
import java.time.Clock
import kotlinx.coroutines.coroutineScope

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
    private val hostKeys: HostKeyStore,
    private val tunnel: SshTunnel,
    private val runnerFactory: (File) -> UnisonRunner,
    private val parserFactory: () -> OutputParser,
    private val unisonDir: File,
    private val clock: Clock,
    private val hostKeyDecisionTimeoutMs: Long = HOST_KEY_DECISION_TIMEOUT_MS,
    private val tunnelCloseGraceMs: Long = TUNNEL_CLOSE_GRACE_MS,
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
        if (!syncMutex.tryLock()) return false
        try {
            runSync(profileId, binary)
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

    private suspend fun runSync(profileId: String, binary: File) {
        activeJob = currentCoroutineContext()[Job]
        clearStaleLocks()
        var profile: Profile? = null
        var process: RunningProcess? = null
        var handle: TunnelHandle? = null
        var success = false
        try {
            val p = profiles.get(profileId) ?: run {
                _state.value = SyncState.Failed(profileId, SyncState.Reason.UNKNOWN, "Profile not found: $profileId")
                return
            }
            profile = p
            _state.value = SyncState.Connecting(profileId)

            val spec = TunnelSpec(
                host = p.host,
                port = p.sshPort,
                user = p.user,
                privateKeyPem = keys.privateKeyPem(p.sshKeyId),
                remoteSocketPort = p.remoteSocketPort,
                transport = p.transport,
                serverCommand = p.serverCommand,
            )
            var hostKeyOutcome = HostKeyOutcome.APPROVED
            val decision = HostKeyDecision { fingerprint ->
                when (hostKeys.verify(p.host, p.sshPort, fingerprint)) {
                    TofuVerdict.APPROVED -> true
                    TofuVerdict.CHANGED -> {
                        hostKeyOutcome = HostKeyOutcome.CHANGED
                        false
                    }
                    TofuVerdict.UNKNOWN -> {
                        val pending = CompletableDeferred<Boolean>()
                        pendingDecision = pending
                        _state.value = SyncState.AwaitingHostKey(profileId, fingerprint)
                        val approved = withTimeoutOrNull(hostKeyDecisionTimeoutMs) { pending.await() }
                        pendingDecision = null
                        when (approved) {
                            true -> {
                                hostKeys.approve(p.host, p.sshPort, fingerprint)
                                true
                            }

                            false -> {
                                hostKeyOutcome = HostKeyOutcome.DECLINED
                                false
                            }

                            null -> {
                                hostKeyOutcome = HostKeyOutcome.TIMED_OUT
                                false
                            }
                        }
                    }
                }
            }
            val opened = try {
                tunnel.open(spec, decision)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                when (hostKeyOutcome) {
                    HostKeyOutcome.TIMED_OUT ->
                        failSync(profileId, p, SyncState.Reason.TUNNEL, hostKeyTimeoutDetail(p))
                    HostKeyOutcome.DECLINED ->
                        failSync(profileId, p, SyncState.Reason.AUTH, hostKeyDeclinedDetail(p))
                    else -> failSync(profileId, p, mapException(t), failureDetail(t))
                }
                return
            }
            handle = opened
            when (hostKeyOutcome) {
                HostKeyOutcome.CHANGED -> {
                    failSync(profileId, p, SyncState.Reason.TUNNEL, "Host key for ${p.host}:${p.sshPort} has changed; refusing to connect")
                    return
                }
                HostKeyOutcome.DECLINED -> {
                    failSync(profileId, p, SyncState.Reason.AUTH, hostKeyDeclinedDetail(p))
                    return
                }
                HostKeyOutcome.TIMED_OUT -> {
                    failSync(profileId, p, SyncState.Reason.TUNNEL, hostKeyTimeoutDetail(p))
                    return
                }
                HostKeyOutcome.APPROVED -> Unit
            }

            unisonDir.mkdirs()
            File(unisonDir, "${p.id}.prf").writeText(PrfGenerator.generate(p, opened.localPort))

            val proc = runnerFactory(binary).start(
                env = mapOf("UNISON" to unisonDir.absolutePath),
                args = listOf(p.id, "-batch"),
            )
            process = proc

            val parser = parserFactory()
            val log = ArrayDeque<String>()
            var progress = 0f
            var versionMismatch: String? = null
            var permissionDenied: String? = null

            val exit: Int? = coroutineScope {
                val settled = CompletableDeferred<Int?>()
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
                        if (permissionDenied == null && line.contains(PERMISSION_DENIED_MARKER)) permissionDenied = line
                        _state.value = SyncState.Syncing(profileId, log.toList(), progress)
                    }
                    settled.complete(proc.exitCode())
                }
                val watcher = launch {
                    opened.awaitClosed()
                    // The remote unison -server exits first on a normal
                    // completion, closing the relay while the local process is
                    // still flushing archives; only treat the closed tunnel as
                    // fatal if the process fails to exit on its own in time.
                    val exited = withTimeoutOrNull(tunnelCloseGraceMs) { settled.await() }
                    if (exited == null) {
                        settled.complete(null)
                        proc.kill()
                    }
                }
                val code = settled.await()
                collector.cancelAndJoin()
                watcher.cancelAndJoin()
                code
            }

            val summary = parser.finalize(exit ?: EXIT_UNKNOWN)
            val mismatch = versionMismatch
            val denied = permissionDenied
            when {
                exit == null -> failSync(profileId, p, SyncState.Reason.TUNNEL, "SSH tunnel closed before unison finished")
                mismatch != null -> failSync(profileId, p, SyncState.Reason.VERSION, mismatch)
                denied != null -> failSync(profileId, p, SyncState.Reason.LOCAL_PERMISSIONS, denied)
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
            handle?.close()
            activeJob = null
        }
    }

    private suspend fun failSync(profileId: String, profile: Profile?, reason: SyncState.Reason, detail: String) {
        profile?.let { markFailed(it) }
        _state.value = SyncState.Failed(profileId, reason, detail)
    }

    private suspend fun markFailed(profile: Profile) {
        profiles.save(profile.copy(lastResult = SyncResult.FAILED))
    }

    private fun logTail(log: List<String>): String = log.takeLast(DETAIL_TAIL_LINES).joinToString(LINE_FEED)

    private fun mapException(t: Throwable): SyncState.Reason = when (t) {
        is UserAuthException -> SyncState.Reason.AUTH
        is TransportException, is ConnectionException, is ConnectException -> SyncState.Reason.TUNNEL
        else -> SyncState.Reason.UNKNOWN
    }

    private fun failureDetail(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private fun hostKeyDeclinedDetail(profile: Profile): String =
        "Host key for ${profile.host}:${profile.sshPort} was declined by the user"

    private fun hostKeyTimeoutDetail(profile: Profile): String =
        "Host key approval for ${profile.host}:${profile.sshPort} timed out; no decision was made"

    private enum class HostKeyOutcome { APPROVED, CHANGED, DECLINED, TIMED_OUT }

    private companion object {
        const val LOG_MAX_LINES = 2000
        const val DETAIL_TAIL_LINES = 20
        const val HOST_KEY_DECISION_TIMEOUT_MS = 300_000L
        const val TUNNEL_CLOSE_GRACE_MS = 10_000L
        const val EXIT_UNKNOWN = -1
        const val LINE_FEED = "\n"
        const val PERMISSION_DENIED_MARKER = "Permission denied"
        val STALE_LOCK = Regex("lk[0-9a-f]{32}")
    }
}
