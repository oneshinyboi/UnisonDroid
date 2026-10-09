package io.unisondroid.app.sync

import io.unisondroid.app.data.HostKeyStore
import io.unisondroid.app.data.JsonStore
import io.unisondroid.app.data.KeyCipher
import io.unisondroid.app.data.KeyVault
import io.unisondroid.app.data.Profile
import io.unisondroid.app.data.ProfileRepository
import io.unisondroid.app.data.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Timeout(60)
class SyncEngineTest {

    @TempDir
    lateinit var dataDir: File

    @TempDir
    lateinit var unisonDir: File

    @TempDir
    lateinit var nativeDir: File

    @Test
    fun `happy path connects writes prf runs unison and finishes in order`() = runTest {
        val tunnelPark = CompletableDeferred<Unit>()
        val lineGate = CompletableDeferred<Unit>()
        var prfAtRunnerStart: String? = null
        val h = harness(
            scripts = listOf(
                ScriptedProcess(
                    lines = listOf(PROGRESS_LINE, SUMMARY_LINE),
                    gateAfter = 1,
                    gate = lineGate,
                ),
            ),
            tunnelPark = tunnelPark,
            onStart = { _, _ -> prfAtRunnerStart = File(unisonDir, "prof1.prf").readText() },
        )
        assertEquals(SyncState.Idle, h.engine.state.value)
        val job = launch { h.engine.requestSync("prof1") }

        val connecting = awaitState(h.engine) { it is SyncState.Connecting }
        assertEquals(SyncState.Connecting("prof1"), connecting)
        assertEquals(listOf("tunnel-open"), h.events)
        assertFalse(File(unisonDir, "prof1.prf").exists(), "prf must be written only after the tunnel is up")
        assertTrue(h.runner.starts.isEmpty(), "runner must not start while parked inside tunnel open")

        tunnelPark.complete(Unit)

        val syncing = awaitState(h.engine) {
            it is SyncState.Syncing && it.log.lastOrNull() == PROGRESS_LINE
        } as SyncState.Syncing
        assertEquals(listOf(PROGRESS_LINE), syncing.log)
        assertEquals(0.5f, syncing.progress)
        assertEquals(listOf("tunnel-open", "runner-start"), h.events)

        lineGate.complete(Unit)
        withTimeout(10_000) { job.join() }

        val finished = h.engine.state.value as SyncState.Finished
        assertEquals("prof1", finished.profileId)
        assertEquals(SyncSummary(transferred = 2, failed = 0, conflicts = 0), finished.summary)
        h.tunnel.assertAllClosed()
        assertFalse(h.runner.processes.single().killed)

        val spec = h.tunnel.specs.single()
        assertEquals(HOST, spec.host)
        assertEquals(SSH_PORT, spec.port)
        assertEquals("syncuser", spec.user)
        assertEquals(REMOTE_SOCKET_PORT, spec.remoteSocketPort)
        assertEquals(h.vault.privateKeyPem(h.keyId), spec.privateKeyPem)

        val (env, args) = h.runner.starts.single()
        assertEquals(mapOf("UNISON" to unisonDir.absolutePath), env)
        assertEquals(listOf("prof1", "-batch"), args)

        val expectedPrf = PrfGenerator.generate(h.profiles.getValue("prof1"), FAKE_LOCAL_PORT)
        assertEquals(expectedPrf, prfAtRunnerStart)
        assertEquals(expectedPrf, File(unisonDir, "prof1.prf").readText())
        assertTrue(expectedPrf.contains("root = /storage/emulated/0/Sync"))
        assertTrue(expectedPrf.contains("root = socket://127.0.0.1:$FAKE_LOCAL_PORT"))
        assertTrue(expectedPrf.contains("perms = 0"))
    }

    @Test
    fun `unknown host key pauses for decision and approval resumes sync`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))),
            knownFingerprint = null,
        )
        val job = launch { h.engine.requestSync("prof1") }

        val awaiting = awaitState(h.engine) { it is SyncState.AwaitingHostKey }
        assertEquals(SyncState.AwaitingHostKey(profileId = "prof1", fingerprint = SERVER_FINGERPRINT), awaiting)
        assertTrue(h.runner.starts.isEmpty(), "runner must not start while host key decision is pending")

        h.engine.respondHostKey(true)
        withTimeout(10_000) { job.join() }

        val finished = h.engine.state.value as SyncState.Finished
        assertEquals("prof1", finished.profileId)
        assertEquals(SERVER_FINGERPRINT, h.hostKeys.known(HOST, SSH_PORT)?.fingerprint)
        assertEquals(1, h.runner.starts.size)
        h.tunnel.assertAllClosed()
    }

    @Test
    fun `declined host key fails with AUTH and closes tunnel`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = emptyList())),
            knownFingerprint = null,
        )
        val job = launch { h.engine.requestSync("prof1") }
        awaitState(h.engine) { it is SyncState.AwaitingHostKey }

        h.engine.respondHostKey(false)
        withTimeout(10_000) { job.join() }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals("prof1", failed.profileId)
        assertEquals(SyncState.Reason.AUTH, failed.reason)
        assertTrue(failed.detail.contains("host key", ignoreCase = true), "detail was: ${failed.detail}")
        h.tunnel.assertAllClosed()
        assertTrue(h.runner.starts.isEmpty())
        assertFalse(File(unisonDir, "prof1.prf").exists())
        assertNull(h.hostKeys.known(HOST, SSH_PORT), "declined fingerprint must not be persisted")
    }

    @Test
    fun `host key approval timeout fails with TUNNEL not a user decline`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = emptyList())),
            knownFingerprint = null,
            hostKeyDecisionTimeoutMs = 100L,
        )
        val job = launch { h.engine.requestSync("prof1") }
        awaitState(h.engine) { it is SyncState.AwaitingHostKey }

        advanceTimeBy(100)
        runCurrent()
        withTimeout(10_000) { job.join() }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals("prof1", failed.profileId)
        assertEquals(SyncState.Reason.TUNNEL, failed.reason)
        assertTrue(failed.detail.contains("timed out", ignoreCase = true), "detail was: ${failed.detail}")
        assertFalse(failed.detail.contains("declined", ignoreCase = true), "detail was: ${failed.detail}")
        assertNull(h.hostKeys.known(HOST, SSH_PORT), "a timed-out decision must not persist a fingerprint")
        h.tunnel.assertAllClosed()
        assertTrue(h.runner.starts.isEmpty())
    }

    @Test
    fun `changed host key fails with TUNNEL and closes tunnel`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = emptyList())),
            knownFingerprint = OTHER_FINGERPRINT,
        )
        val job = launch { h.engine.requestSync("prof1") }
        withTimeout(10_000) { job.join() }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals("prof1", failed.profileId)
        assertEquals(SyncState.Reason.TUNNEL, failed.reason)
        h.tunnel.assertAllClosed()
        assertTrue(h.runner.starts.isEmpty())
        assertFalse(File(unisonDir, "prof1.prf").exists())
        assertEquals(OTHER_FINGERPRINT, h.hostKeys.known(HOST, SSH_PORT)?.fingerprint)
    }

    @Test
    fun `second request while a sync is running returns false and state remains Syncing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(PROGRESS_LINE), gateAfter = 1, gate = gate)),
        )
        val job = launch { h.engine.requestSync("prof1") }
        awaitState(h.engine) { it is SyncState.Syncing }
        val syncing = h.engine.state.value

        val proceeded = withTimeout(10_000) { h.engine.requestSync("prof2") }

        assertFalse(proceeded)
        assertTrue(syncing is SyncState.Syncing)
        assertEquals(syncing, h.engine.state.value, "rejected request must not touch state")
        assertEquals(1, h.runner.starts.size, "rejected request must not start another run")

        gate.complete(Unit)
        withTimeout(10_000) { job.join() }

        val finished = h.engine.state.value as SyncState.Finished
        assertEquals("prof1", finished.profileId)
    }

    @Test
    fun `cancel kills process closes tunnel and reports CANCELLED`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(PROGRESS_LINE), gateAfter = 1, gate = gate)),
        )
        val job = launch { h.engine.requestSync("prof1") }
        awaitState(h.engine) { it is SyncState.Syncing }

        h.engine.cancel()
        withTimeout(10_000) { job.join() }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals("prof1", failed.profileId)
        assertEquals(SyncState.Reason.CANCELLED, failed.reason)
        assertTrue(h.runner.processes.single().killed)
        h.tunnel.assertAllClosed()
        assertEquals(SyncResult.FAILED, h.repo.get("prof1")?.lastResult)
    }

    @Test
    fun `tunnel closing mid-sync kills process and fails with TUNNEL`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(PROGRESS_LINE), gateAfter = 1, gate = gate)),
        )
        val job = launch { h.engine.requestSync("prof1") }
        awaitState(h.engine) { it is SyncState.Syncing }

        h.tunnel.handles.single().closedOrDead.complete(Unit)

        withTimeout(10_000) { job.join() }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals("prof1", failed.profileId)
        assertEquals(SyncState.Reason.TUNNEL, failed.reason)
        assertTrue(h.runner.processes.single().killed)
        h.tunnel.assertAllClosed()
    }

    @Test
    fun `binary missing fails with BINARY_MISSING before any IO`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))),
            binaryAvailable = false,
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals("prof1", failed.profileId)
        assertEquals(SyncState.Reason.BINARY_MISSING, failed.reason)
        assertTrue(h.tunnel.specs.isEmpty(), "tunnel must not be opened when binary is missing")
        assertTrue(h.runner.starts.isEmpty(), "runner must not start when binary is missing")
        assertFalse(File(unisonDir, "prof1.prf").exists(), "prf must not be written when binary is missing")
    }

    @Test
    fun `nonzero exit maps to Failed EXIT with detail tail`() = runTest {
        val h = harness(
            scripts = listOf(
                ScriptedProcess(
                    lines = listOf(
                        "[wnt] ...  1/2 KiB  a.txt",
                        "Error: something exploded",
                        "Unison server: fatal",
                    ),
                    exit = 3,
                ),
            ),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals("prof1", failed.profileId)
        assertEquals(SyncState.Reason.EXIT, failed.reason)
        assertTrue(failed.detail.contains("something exploded"), "detail was: ${failed.detail}")
        assertTrue(failed.detail.contains("Unison server: fatal"), "detail was: ${failed.detail}")
        h.tunnel.assertAllClosed()
        assertEquals(SyncResult.FAILED, h.repo.get("prof1")?.lastResult)
    }

    @Test
    fun `parser VersionMismatch event fails with VERSION even on exit zero`() = runTest {
        val h = harness(
            scripts = listOf(
                ScriptedProcess(
                    lines = listOf(
                        "Error: Received unexpected header from the server:",
                        "This can happen because you have different versions of Unison",
                    ),
                    exit = 0,
                ),
            ),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals("prof1", failed.profileId)
        assertEquals(SyncState.Reason.VERSION, failed.reason)
        assertTrue(failed.detail.contains("Received unexpected header"), "detail was: ${failed.detail}")
        h.tunnel.assertAllClosed()
    }

    @Test
    fun `permission denied output fails with LOCAL_PERMISSIONS`() = runTest {
        val h = harness(
            scripts = listOf(
                ScriptedProcess(
                    lines = listOf("[FAILED] /storage/emulated/0/Sync/notes/todo.txt: Permission denied"),
                    exit = 0,
                ),
            ),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals("prof1", failed.profileId)
        assertEquals(SyncState.Reason.LOCAL_PERMISSIONS, failed.reason)
        assertTrue(failed.detail.contains("Permission denied"), "detail was: ${failed.detail}")
        assertEquals(SyncResult.FAILED, h.repo.get("prof1")?.lastResult)
        h.tunnel.assertAllClosed()
    }

    @Test
    fun `log buffer caps at the last 2000 lines`() = runTest {
        val total = 2500
        val lastLine = "line-%04d".format(total)
        val gate = CompletableDeferred<Unit>()
        val h = harness(
            scripts = listOf(
                ScriptedProcess(
                    lines = (1..total).map { "line-%04d".format(it) },
                    gateAfter = total,
                    gate = gate,
                ),
            ),
        )
        val job = launch { h.engine.requestSync("prof1") }

        val syncing = awaitState(h.engine) {
            it is SyncState.Syncing && it.log.lastOrNull() == lastLine
        } as SyncState.Syncing
        assertEquals(2000, syncing.log.size)
        assertEquals("line-0501", syncing.log.first())
        assertEquals(lastLine, syncing.log.last())

        gate.complete(Unit)
        withTimeout(10_000) { job.join() }

        val finished = h.engine.state.value as SyncState.Finished
        assertEquals(SyncSummary(transferred = 0, failed = 0, conflicts = 0), finished.summary)
    }

    @Test
    fun `profile lastResult updated to OK WARNINGS FAILED per summary`() = runTest {
        val h = harness(
            scripts = listOf(
                ScriptedProcess(lines = listOf(SUMMARY_LINE), exit = 0),
                ScriptedProcess(lines = listOf("[CONFLICT] notes/plan.txt", SUMMARY_LINE), exit = 0),
                ScriptedProcess(lines = listOf("Error: boom"), exit = 1),
            ),
            profileIds = listOf("prof1", "prof2", "prof3"),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }
        withTimeout(10_000) { h.engine.requestSync("prof2") }
        withTimeout(10_000) { h.engine.requestSync("prof3") }

        assertEquals(SyncResult.OK, h.repo.get("prof1")?.lastResult)
        assertEquals(SyncResult.WARNINGS, h.repo.get("prof2")?.lastResult)
        assertEquals(SyncResult.FAILED, h.repo.get("prof3")?.lastResult)
        assertEquals(FIXED_NOW_MILLIS, h.repo.get("prof1")?.lastSyncedAt)
    }

    private suspend fun awaitState(engine: SyncEngine, predicate: (SyncState) -> Boolean): SyncState =
        withTimeout(10_000) { engine.state.first(predicate) }

    private suspend fun harness(
        scripts: List<ScriptedProcess>,
        profileIds: List<String> = listOf("prof1", "prof2"),
        knownFingerprint: String? = SERVER_FINGERPRINT,
        binaryAvailable: Boolean = true,
        tunnelPark: CompletableDeferred<Unit>? = null,
        hostKeyDecisionTimeoutMs: Long = 300_000L,
        onStart: ((Map<String, String>, List<String>) -> Unit)? = null,
    ): Harness {
        if (binaryAvailable) {
            File(nativeDir, "libunison.so").writeText("fake-binary")
        }
        val store = JsonStore(dataDir)
        val repo = ProfileRepository(store)
        val vault = KeyVault(store, IdentityCipher)
        val hostKeys = HostKeyStore(store)
        val key = vault.generate("phone-key")
        val profiles = profileIds.associateWith { profile(it, key.id) }
        profiles.values.forEach { repo.save(it) }
        if (knownFingerprint != null) {
            hostKeys.approve(HOST, SSH_PORT, knownFingerprint)
        }
        val events = mutableListOf<String>()
        val tunnel = FakeTunnel(parkBeforeReturn = tunnelPark, events = events)
        val runner = FakeRunner(scripts, events, onStart)
        val engine = SyncEngine(
            binaryLocator = BinaryLocator(nativeDir),
            profiles = repo,
            keys = vault,
            hostKeys = hostKeys,
            tunnel = tunnel,
            runnerFactory = { _ -> runner },
            parserFactory = { OutputParser() },
            unisonDir = unisonDir,
            clock = Clock.fixed(Instant.ofEpochMilli(FIXED_NOW_MILLIS), ZoneOffset.UTC),
            hostKeyDecisionTimeoutMs = hostKeyDecisionTimeoutMs,
        )
        return Harness(engine, repo, vault, hostKeys, tunnel, runner, events, profiles, key.id)
    }

    private fun profile(id: String, sshKeyId: String) = Profile(
        id = id,
        name = "Profile $id",
        localRoot = "/storage/emulated/0/Sync",
        remoteRoot = "/srv/sync",
        host = HOST,
        sshPort = SSH_PORT,
        user = "syncuser",
        remoteSocketPort = REMOTE_SOCKET_PORT,
        sshKeyId = sshKeyId,
    )

    private data class Harness(
        val engine: SyncEngine,
        val repo: ProfileRepository,
        val vault: KeyVault,
        val hostKeys: HostKeyStore,
        val tunnel: FakeTunnel,
        val runner: FakeRunner,
        val events: MutableList<String>,
        val profiles: Map<String, Profile>,
        val keyId: String,
    )

    private object IdentityCipher : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(blob: ByteArray): ByteArray = blob
    }

    private class ScriptedProcess(
        val lines: List<String>,
        val exit: Int = 0,
        val gateAfter: Int = -1,
        val gate: CompletableDeferred<Unit>? = null,
    )

    private class FakeRunningProcess(val script: ScriptedProcess) : RunningProcess(scriptedOutput(script)) {
        var killed = false
        override fun kill() {
            killed = true
        }

        override suspend fun exitCode(): Int = script.exit
    }

    private class FakeRunner(
        private val scripts: List<ScriptedProcess>,
        private val events: MutableList<String>,
        private val onStart: ((Map<String, String>, List<String>) -> Unit)? = null,
    ) : UnisonRunner(FAKE_BINARY) {

        val starts = mutableListOf<Pair<Map<String, String>, List<String>>>()
        val processes = mutableListOf<FakeRunningProcess>()
        private val pending = ArrayDeque(scripts)

        override fun start(env: Map<String, String>, args: List<String>): RunningProcess {
            starts += env.toMap() to args.toList()
            events += "runner-start"
            onStart?.invoke(env, args)
            val process = FakeRunningProcess(pending.removeFirstOrNull() ?: scripts.last())
            processes += process
            return process
        }
    }

    private class FakeHandle : TunnelHandle {
        override val localPort: Int = FAKE_LOCAL_PORT
        var closed = false
        var approved = false
        val closedOrDead = CompletableDeferred<Unit>()

        override fun close() {
            closed = true
            closedOrDead.complete(Unit)
        }

        override suspend fun awaitClosed() = closedOrDead.await()
    }

    private class FakeTunnel(
        private val fingerprint: String = SERVER_FINGERPRINT,
        private val parkBeforeReturn: CompletableDeferred<Unit>? = null,
        private val events: MutableList<String> = mutableListOf(),
    ) : SshTunnel {

        val specs = mutableListOf<TunnelSpec>()
        val handles = mutableListOf<FakeHandle>()

        override suspend fun open(spec: TunnelSpec, decision: HostKeyDecision): TunnelHandle {
            events += "tunnel-open"
            specs += spec
            val handle = FakeHandle()
            handles += handle
            handle.approved = decision.decide(fingerprint)
            parkBeforeReturn?.await()
            return handle
        }

        fun assertAllClosed() = handles.forEach { assertTrue(it.closed, "tunnel handle was left open") }
    }

    private companion object {
        const val FIXED_NOW_MILLIS = 1_760_000_000_000L
        const val FAKE_LOCAL_PORT = 23456
        const val HOST = "sync.example.com"
        const val SSH_PORT = 2222
        const val REMOTE_SOCKET_PORT = 22333
        val SERVER_FINGERPRINT = "SHA256:" + "A".repeat(43)
        val OTHER_FINGERPRINT = "SHA256:" + "B".repeat(43)
        val PROGRESS_LINE = "[wnt] ...  5/10 KiB  photos/vacation.jpg"
        val SUMMARY_LINE = "Synchronization complete at 21:33:33  (2 items transferred, 0 skipped, 0 failed)"
        val FAKE_BINARY = File("/nowhere/libunison.so")

        fun scriptedOutput(script: ScriptedProcess): Flow<String> = flow {
            script.lines.forEachIndexed { index, line ->
                emit(line)
                if (index + 1 == script.gateAfter) script.gate?.await()
            }
        }
    }
}
