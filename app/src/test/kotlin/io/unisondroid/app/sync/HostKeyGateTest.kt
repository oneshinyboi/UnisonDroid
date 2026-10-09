package io.unisondroid.app.sync

import io.unisondroid.app.data.HostKeyStore
import io.unisondroid.app.data.JsonStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HostKeyGateTest {

    @TempDir
    lateinit var dir: File

    private val ed25519 = HostKeyEntry(
        knownHostsLine = "[veryshiny.net]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5 ed25519-hostkey",
        keyType = "ssh-ed25519",
        fingerprint = "SHA256:ed25519fingerprint",
    )
    private val rsa = HostKeyEntry(
        knownHostsLine = "[veryshiny.net]:2222 ssh-rsa AAAAB3NzaC1yc2E rsa-hostkey",
        keyType = "ssh-rsa",
        fingerprint = "SHA256:rsafingerprint",
    )

    private fun gate(tool: FakeSshTool): HostKeyGate {
        val store = JsonStore(dir)
        return HostKeyGate(tool, HostKeyStore(store), File(dir, "ssh/known_hosts"))
    }

    @Test
    fun `unknown host scans prompts and persists on approval`() = runTest {
        val tool = FakeSshTool().apply { scannedHostKeys = listOf(ed25519, rsa) }
        val hostKeys = HostKeyStore(JsonStore(dir))
        val knownHosts = File(dir, "ssh/known_hosts")
        val g = HostKeyGate(tool, hostKeys, knownHosts)
        val prompted = mutableListOf<String>()

        val outcome = g.ensureTrusted("veryshiny.net", 2222, 300_000L) { fp ->
            prompted += fp
            true
        }

        assertEquals(HostKeyOutcome.Trusted, outcome)
        assertEquals(listOf("SHA256:ed25519fingerprint"), prompted)
        assertEquals(listOf("veryshiny.net" to 2222), tool.scanCalls)
        assertTrue(knownHosts.readText().contains(ed25519.knownHostsLine), "got:\n${knownHosts.readText()}")
        assertFalse(knownHosts.readText().contains(rsa.knownHostsLine))
        assertEquals("SHA256:ed25519fingerprint", hostKeys.known("veryshiny.net", 2222)?.fingerprint)
        val perms = java.nio.file.Files.getPosixFilePermissions(knownHosts.toPath())
        assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test
    fun `declined prompt returns Declined and persists nothing`() = runTest {
        val tool = FakeSshTool().apply { scannedHostKeys = listOf(ed25519) }
        val hostKeys = HostKeyStore(JsonStore(dir))
        val knownHosts = File(dir, "ssh/known_hosts")
        val g = HostKeyGate(tool, hostKeys, knownHosts)

        val outcome = g.ensureTrusted("veryshiny.net", 2222, 300_000L) { false }

        assertEquals(HostKeyOutcome.Declined("SHA256:ed25519fingerprint"), outcome)
        assertFalse(knownHosts.exists(), "declined approval must not write known_hosts")
        assertEquals(null, hostKeys.known("veryshiny.net", 2222))
    }

    @Test
    fun `prompt timeout returns TimedOut`() = runTest {
        val tool = FakeSshTool().apply { scannedHostKeys = listOf(ed25519) }
        val g = gate(tool)
        var outcome: HostKeyOutcome? = null

        val job = launch {
            outcome = g.ensureTrusted("veryshiny.net", 2222, 100L) {
                delay(10_000)
                true
            }
        }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        job.join()

        assertEquals(HostKeyOutcome.TimedOut("SHA256:ed25519fingerprint"), outcome)
    }

    @Test
    fun `known host returns Trusted without scanning`() = runTest {
        val tool = FakeSshTool().apply { scannedHostKeys = listOf(ed25519) }
        val hostKeys = HostKeyStore(JsonStore(dir))
        hostKeys.approve("veryshiny.net", 2222, "SHA256:ed25519fingerprint")
        val g = HostKeyGate(tool, hostKeys, File(dir, "ssh/known_hosts"))

        val outcome = g.ensureTrusted("veryshiny.net", 2222, 300_000L) { true }

        assertEquals(HostKeyOutcome.Trusted, outcome)
        assertTrue(tool.scanCalls.isEmpty(), "a known host must not be scanned")
    }

    @Test
    fun `scan failure returns ScanFailed`() = runTest {
        val tool = FakeSshTool().apply { scanException = SshToolException("connection refused") }
        val g = gate(tool)

        val outcome = g.ensureTrusted("veryshiny.net", 2222, 300_000L) { true }

        assertEquals(HostKeyOutcome.ScanFailed("connection refused"), outcome)
    }

    @Test
    fun `scan without ed25519 approves first scanned key`() = runTest {
        val tool = FakeSshTool().apply { scannedHostKeys = listOf(rsa) }
        val g = gate(tool)
        val prompted = mutableListOf<String>()

        val outcome = g.ensureTrusted("veryshiny.net", 2222, 300_000L) { fp ->
            prompted += fp
            true
        }

        assertEquals(HostKeyOutcome.Trusted, outcome)
        assertEquals(listOf("SHA256:rsafingerprint"), prompted)
    }

    @Test
    fun `keyscan is asked for the profile port`() = runTest {
        val tool = FakeSshTool().apply { scannedHostKeys = listOf(ed25519) }
        val g = gate(tool)

        g.ensureTrusted("veryshiny.net", 2222, 300_000L) { true }

        assertEquals(listOf("veryshiny.net" to 2222), tool.scanCalls)
    }
}
