package io.unisondroid.app.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProcessSshToolTest {

    @TempDir
    lateinit var dir: File

    private fun script(name: String, body: String): File {
        val file = File(dir, name)
        file.writeText("#!/bin/sh\n$body\n")
        file.setExecutable(true)
        return file
    }

    @Test
    @Timeout(8)
    fun `derivePublicKey times out instead of hanging on a stuck child`() {
        val stuck = script("stuck-keygen", "sleep 30")
        val tool = ProcessSshTool(stuck, stuck, dir, timeoutMs = 500)

        val start = System.currentTimeMillis()
        assertThrows(SshToolException::class.java) { tool.derivePublicKey("pem-body") }

        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 5_000, "the call must return promptly, took ${elapsed}ms")
    }

    @Test
    fun `scanHostKeys reads the key type from the host-key line, not the fingerprint comment`() {
        val keyscan = script(
            "fake-keyscan",
            "echo '[veryshiny.net]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5 testhost'\n" +
                "echo '[veryshiny.net]:2222 ssh-rsa AAAAB3NzaC1yc2E testhost2'",
        )
        val keygen = script(
            "fake-keygen",
            "while IFS= read -r line; do\n" +
                "  case \"\$line\" in\n" +
                "    *ssh-ed25519*) echo '256 SHA256:edfp host (ED25519)' ;;\n" +
                "    *ssh-rsa*) echo '3072 SHA256:rsafp host (RSA)' ;;\n" +
                "  esac\n" +
                "done",
        )
        val tool = ProcessSshTool(keygen, keyscan, dir)

        val entries = tool.scanHostKeys("veryshiny.net", 2222)

        assertEquals(2, entries.size)
        assertEquals("ssh-ed25519", entries[0].keyType)
        assertEquals("SHA256:edfp", entries[0].fingerprint)
        assertEquals("ssh-rsa", entries[1].keyType)
    }
}
