package io.unisondroid.app.sync

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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    lateinit var sshHome: File

    @TempDir
    lateinit var nativeDir: File

    @Test
    fun `stale local lock files are cleared before unison runs`() = runTest {
        val staleLock = File(unisonDir, "lk" + "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6")
        val archive = File(unisonDir, "ar" + "0".repeat(32))
        staleLock.writeText("stale")
        archive.writeText("archive")
        var lockPresentAtRunnerStart = true
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))),
            onStart = { _, _ -> lockPresentAtRunnerStart = staleLock.exists() },
        )

        val job = launch { h.engine.requestSync("prof1") }
        withTimeout(10_000) { job.join() }

        assertTrue(h.engine.state.value is SyncState.Finished)
        assertFalse(staleLock.exists(), "stale lk* lock must be cleared")
        assertTrue(archive.exists(), "non-lock files must be preserved")
        assertFalse(lockPresentAtRunnerStart, "lock must be gone before unison starts")
    }

    @Test
    fun `happy path runs unison over ssh and finishes`() = runTest {
        val lineGate = CompletableDeferred<Unit>()
        var prfAtRunnerStart: String? = null
        var keyExistedAtRunnerStart = false
        val h = harness(
            scripts = listOf(
                ScriptedProcess(lines = listOf(PROGRESS_LINE, SUMMARY_LINE), gateAfter = 1, gate = lineGate),
            ),
            onStart = { _, _ ->
                prfAtRunnerStart = File(unisonDir, "prof1.prf").readText()
                keyExistedAtRunnerStart = sshHome.listFiles()?.any { it.isFile } == true
            },
        )
        assertEquals(SyncState.Idle, h.engine.state.value)

        val job = launch { h.engine.requestSync("prof1") }

        val syncing = awaitState(h.engine) {
            it is SyncState.Syncing && it.log.lastOrNull() == PROGRESS_LINE
        } as SyncState.Syncing
        assertEquals(0.5f, syncing.progress)

        lineGate.complete(Unit)
        withTimeout(10_000) { job.join() }

        val finished = h.engine.state.value as SyncState.Finished
        assertEquals("prof1", finished.profileId)
        assertEquals(SyncSummary(transferred = 2, failed = 0, conflicts = 0), finished.summary)
        assertFalse(h.runner.processes.single().killed)
        assertTrue(keyExistedAtRunnerStart, "the private key file must exist while unison runs")
        assertFalse(File(sshHome, h.keyId).exists(), "the private key file must be deleted after the sync")

        val prf = File(unisonDir, "prof1.prf").readText()
        assertEquals(prf, prfAtRunnerStart)
        assertTrue(prf.contains("root = /storage/emulated/0/Sync"), "got:\n$prf")
        assertTrue(prf.contains("root = ssh://syncuser@$HOST//srv/sync"), "got:\n$prf")
        val sshargs = prf.lines().first { it.startsWith("sshargs = ") }
        assertTrue(sshargs.startsWith("sshargs = -F "), "got:\n$prf")
        assertTrue(prf.contains("perms = 0"))

        val config = File(sshHome, "ssh_config").readText()
        assertTrue(config.contains("Port $SSH_PORT"), "got:\n$config")

        val (env, args) = h.runner.starts.single()
        assertEquals(mapOf("UNISON" to unisonDir.absolutePath), env)
        assertEquals(listOf("prof1", "-batch"), args)
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
        assertFalse(File(unisonDir, "prof1.prf").exists(), "prf must not be written before trust")

        h.engine.respondHostKey(true)
        withTimeout(10_000) { job.join() }

        val finished = h.engine.state.value as SyncState.Finished
        assertEquals("prof1", finished.profileId)
        val knownHosts = File(sshHome, "known_hosts")
        assertTrue(knownHosts.readText().contains(SERVER_KNOWN_HOSTS_LINE), "got:\n${knownHosts.readText()}")
        assertEquals(1, h.runner.starts.size)
    }

    @Test
    fun `declined host key fails with AUTH and never runs unison`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = emptyList())),
            knownFingerprint = null,
        )
        val job = launch { h.engine.requestSync("prof1") }
        awaitState(h.engine) { it is SyncState.AwaitingHostKey }

        h.engine.respondHostKey(false)
        withTimeout(10_000) { job.join() }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncState.Reason.AUTH, failed.reason)
        assertTrue(failed.detail.contains("host key", ignoreCase = true), "detail was: ${failed.detail}")
        assertTrue(h.runner.starts.isEmpty())
        assertFalse(File(unisonDir, "prof1.prf").exists())
        assertFalse(File(sshHome, "known_hosts").exists(), "declined fingerprint must not be persisted")
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
        assertEquals(SyncState.Reason.TUNNEL, failed.reason)
        assertTrue(failed.detail.contains("timed out", ignoreCase = true), "detail was: ${failed.detail}")
        assertFalse(File(sshHome, "known_hosts").exists(), "a timed-out decision must not persist a fingerprint")
        assertTrue(h.runner.starts.isEmpty())
    }

    @Test
    fun `host key scan failure fails with TUNNEL`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = emptyList())),
            knownFingerprint = null,
            scannedHostKeys = emptyList(),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncState.Reason.TUNNEL, failed.reason)
        assertTrue(failed.detail.contains("scan", ignoreCase = true), "detail was: ${failed.detail}")
        assertTrue(h.runner.starts.isEmpty())
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
        assertEquals(syncing, h.engine.state.value, "rejected request must not touch state")
        assertEquals(1, h.runner.starts.size, "rejected request must not start another run")

        gate.complete(Unit)
        withTimeout(10_000) { job.join() }
        assertTrue(h.engine.state.value is SyncState.Finished)
    }

    @Test
    fun `cancel kills process and reports CANCELLED`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(PROGRESS_LINE), gateAfter = 1, gate = gate)),
        )
        val job = launch { h.engine.requestSync("prof1") }
        awaitState(h.engine) { it is SyncState.Syncing }

        h.engine.cancel()
        withTimeout(10_000) { job.join() }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncState.Reason.CANCELLED, failed.reason)
        assertTrue(h.runner.processes.single().killed)
        assertEquals(SyncResult.FAILED, h.repo.get("prof1")?.lastResult)
    }

    @Test
    fun `binary missing fails with BINARY_MISSING before any IO`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))),
            binaryAvailable = false,
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncState.Reason.BINARY_MISSING, failed.reason)
        assertTrue(failed.detail.contains("libunison.so"), "detail was: ${failed.detail}")
        assertTrue(h.runner.starts.isEmpty())
        assertFalse(File(unisonDir, "prof1.prf").exists())
    }

    @Test
    fun `ssh binary missing fails with BINARY_MISSING before any IO`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf(SUMMARY_LINE))),
            sshBinaryAvailable = false,
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncState.Reason.BINARY_MISSING, failed.reason)
        assertTrue(failed.detail.contains("libssh.so"), "detail was: ${failed.detail}")
        assertTrue(h.runner.starts.isEmpty())
    }

    @Test
    fun `nonzero exit maps to Failed EXIT with detail tail`() = runTest {
        val h = harness(
            scripts = listOf(
                ScriptedProcess(
                    lines = listOf("[wnt] ...  1/2 KiB  a.txt", "Error: something exploded", "Unison server: fatal"),
                    exit = 3,
                ),
            ),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncState.Reason.EXIT, failed.reason)
        assertTrue(failed.detail.contains("something exploded"), "detail was: ${failed.detail}")
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
        assertEquals(SyncState.Reason.VERSION, failed.reason)
        assertTrue(failed.detail.contains("Received unexpected header"), "detail was: ${failed.detail}")
    }

    @Test
    fun `local permission denied output fails with LOCAL_PERMISSIONS`() = runTest {
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
        assertEquals(SyncState.Reason.LOCAL_PERMISSIONS, failed.reason)
        assertEquals(SyncResult.FAILED, h.repo.get("prof1")?.lastResult)
    }

    @Test
    fun `publickey auth failure maps to AUTH`() = runTest {
        val h = harness(
            scripts = listOf(
                ScriptedProcess(
                    lines = listOf("Diamond@veryshiny.net: Permission denied (publickey,password)."),
                    exit = 255,
                ),
            ),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncState.Reason.AUTH, failed.reason)
    }

    @Test
    fun `host key verification failure maps to TUNNEL`() = runTest {
        val h = harness(
            scripts = listOf(
                ScriptedProcess(
                    lines = listOf(
                        "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                        "Host key verification failed.",
                    ),
                    exit = 255,
                ),
            ),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncState.Reason.TUNNEL, failed.reason)
    }

    @Test
    fun `lost connection output maps to TUNNEL`() = runTest {
        val h = harness(
            scripts = listOf(
                ScriptedProcess(
                    lines = listOf("Fatal error: Lost connection with the server"),
                    exit = 2,
                ),
            ),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        val failed = h.engine.state.value as SyncState.Failed
        assertEquals(SyncState.Reason.TUNNEL, failed.reason)
    }

    @Test
    fun `private key file is deleted even when the sync fails`() = runTest {
        val h = harness(
            scripts = listOf(ScriptedProcess(lines = listOf("Error: boom"), exit = 1)),
        )

        withTimeout(10_000) { h.engine.requestSync("prof1") }

        assertTrue(h.engine.state.value is SyncState.Failed)
        assertFalse(File(sshHome, h.keyId).exists(), "key material must not linger after a failure")
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

        gate.complete(Unit)
        withTimeout(10_000) { job.join() }
        assertEquals(SyncSummary(transferred = 0, failed = 0, conflicts = 0), (h.engine.state.value as SyncState.Finished).summary)
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
        sshBinaryAvailable: Boolean = true,
        hostKeyDecisionTimeoutMs: Long = 300_000L,
        scannedHostKeys: List<HostKeyEntry> = listOf(
            HostKeyEntry(
                knownHostsLine = SERVER_KNOWN_HOSTS_LINE,
                keyType = "ssh-ed25519",
                fingerprint = SERVER_FINGERPRINT,
            ),
        ),
        onStart: ((Map<String, String>, List<String>) -> Unit)? = null,
    ): Harness {
        if (binaryAvailable) File(nativeDir, "libunison.so").writeText("fake-binary")
        if (sshBinaryAvailable) File(nativeDir, "libssh.so").writeText("fake-ssh")
        val store = JsonStore(dataDir)
        val repo = ProfileRepository(store)
        val vault = KeyVault(store, IdentityCipher, FakeSshTool())
        val key = vault.generate("phone-key")
        val profiles = profileIds.associateWith { profile(it, key.id) }
        profiles.values.forEach { repo.save(it) }
        val events = mutableListOf<String>()
        val sshTool = FakeSshTool().apply {
            this.scannedHostKeys = scannedHostKeys
            this.hostIsKnown = knownFingerprint != null
        }
        val runner = FakeRunner(scripts, events, onStart)
        val engine = SyncEngine(
            binaryLocator = BinaryLocator(nativeDir),
            profiles = repo,
            keys = vault,
            sshTool = sshTool,
            runnerFactory = { _ -> runner },
            parserFactory = { OutputParser() },
            unisonDir = unisonDir,
            sshHome = sshHome,
            clock = Clock.fixed(Instant.ofEpochMilli(FIXED_NOW_MILLIS), ZoneOffset.UTC),
            hostKeyDecisionTimeoutMs = hostKeyDecisionTimeoutMs,
        )
        return Harness(engine, repo, vault, sshTool, runner, events, profiles, key.id)
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
        val sshTool: FakeSshTool,
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

    private companion object {
        const val FIXED_NOW_MILLIS = 1_760_000_000_000L
        const val HOST = "sync.example.com"
        const val SSH_PORT = 2222
        const val REMOTE_SOCKET_PORT = 22333
        val SERVER_FINGERPRINT = "SHA256:" + "A".repeat(43)
        val SERVER_KNOWN_HOSTS_LINE = "[$HOST]:$SSH_PORT ssh-ed25519 AAAAad-hoc"
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
