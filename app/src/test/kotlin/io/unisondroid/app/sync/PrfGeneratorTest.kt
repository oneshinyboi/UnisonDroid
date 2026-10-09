package io.unisondroid.app.sync

import io.unisondroid.app.data.Profile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PrfGeneratorTest {

    private fun profile(
        localRoot: String = "/storage/emulated/0/Documents",
        remoteRoot: String = "/srv/sync",
        sshPort: Int = 22,
        serverCommand: String = "unison",
        ignorePatterns: List<String> = emptyList(),
        advancedPrefs: String = "",
    ) = Profile(
        id = "p1",
        name = "Phone",
        localRoot = localRoot,
        remoteRoot = remoteRoot,
        host = "server.example",
        sshPort = sshPort,
        user = "diamond",
        sshKeyId = "key-1",
        serverCommand = serverCommand,
        ignorePatterns = ignorePatterns,
        advancedPrefs = advancedPrefs,
    )

    private fun sshCommand(port: Int = 22) = SshCommand(
        binary = File("/data/app/lib/arm64/libssh.so"),
        keyFile = File("/data/data/io.unisondroid.app/no_backup/ssh/key-1"),
        knownHosts = File("/data/data/io.unisondroid.app/no_backup/ssh/known_hosts"),
        port = port,
    )

    @Test
    fun `roots and ssh line come first in order`() {
        val out = PrfGenerator.generate(profile(), sshCommand())
        val lines = out.lines()

        assertEquals("root = /storage/emulated/0/Documents", lines[0])
        assertEquals("root = ssh://diamond@server.example/srv/sync", lines[1])
        assertEquals(
            "ssh = /data/app/lib/arm64/libssh.so" +
                " -F none" +
                " -i /data/data/io.unisondroid.app/no_backup/ssh/key-1" +
                " -o UserKnownHostsFile=/data/data/io.unisondroid.app/no_backup/ssh/known_hosts" +
                " -o StrictHostKeyChecking=yes" +
                " -o BatchMode=yes" +
                " -o IdentitiesOnly=yes" +
                " -o LogLevel=ERROR" +
                " -p 22",
            lines[2],
        )
        assertEquals(listOf("perms = 0", "links = false", "fat = true"), lines.subList(3, 6))
    }

    @Test
    fun `no socket root is ever emitted`() {
        val out = PrfGenerator.generate(profile(), sshCommand())
        assertFalse(out.contains("socket://"), "got:\n$out")
    }

    @Test
    fun `servercmd is emitted only when the command differs from unison`() {
        assertFalse(PrfGenerator.generate(profile(), sshCommand()).contains("servercmd"))

        val custom = PrfGenerator.generate(profile(serverCommand = "/usr/local/bin/unison"), sshCommand())
        assertTrue(custom.contains("servercmd = /usr/local/bin/unison\n"), "got:\n$custom")
    }

    @Test
    fun `roots with spaces and unicode are emitted verbatim`() {
        val p = profile(
            localRoot = "/storage/emulated/0/My Folder/中文",
            remoteRoot = "/home/Diamond/my folder ♥",
        )
        val out = PrfGenerator.generate(p, sshCommand())

        assertTrue(out.contains("root = /storage/emulated/0/My Folder/中文\n"), "got:\n$out")
        assertTrue(out.contains("root = ssh://diamond@server.example/home/Diamond/my folder ♥\n"), "got:\n$out")
    }

    @Test
    fun `non-default port appears only in the ssh line`() {
        val out = PrfGenerator.generate(profile(sshPort = 2222), sshCommand(port = 2222))

        assertTrue(out.contains(" -p 2222"), "got:\n$out")
        assertFalse(
            out.contains(":2222"),
            "the port must not appear in the ssh:// root, got:\n$out",
        )
    }

    @Test
    fun `each ignore pattern emits ignore = Path line`() {
        val out = PrfGenerator.generate(
            profile(ignorePatterns = listOf("*.tmp", ".thumbnails", "My Folder/Keep*")),
            sshCommand(),
        )

        assertTrue(out.contains("ignore = Path *.tmp\n"))
        assertTrue(out.contains("ignore = Path .thumbnails\n"))
        assertTrue(out.contains("ignore = Path My Folder/Keep*\n"))
    }

    @Test
    fun `advanced block appended after ignores`() {
        val out = PrfGenerator.generate(
            profile(ignorePatterns = listOf("*.bak"), advancedPrefs = "fastcheck = false\n"),
            sshCommand(),
        )

        assertTrue(out.indexOf("ignore = Path *.bak") < out.indexOf("fastcheck = false"))
    }

    @Test
    fun `empty ignores and advanced leave no blank residue`() {
        val expected = "root = /storage/emulated/0/Documents\n" +
            "root = ssh://diamond@server.example/srv/sync\n" +
            "ssh = /data/app/lib/arm64/libssh.so -F none -i /data/data/io.unisondroid.app/no_backup/ssh/key-1" +
            " -o UserKnownHostsFile=/data/data/io.unisondroid.app/no_backup/ssh/known_hosts" +
            " -o StrictHostKeyChecking=yes -o BatchMode=yes -o IdentitiesOnly=yes" +
            " -o LogLevel=ERROR -p 22\n" +
            "perms = 0\n" +
            "links = false\n" +
            "fat = true\n"

        assertEquals(expected, PrfGenerator.generate(profile(), sshCommand()))
    }

    @Test
    fun `advanced without trailing newline still ends with single newline`() {
        val out = PrfGenerator.generate(profile(advancedPrefs = "fastcheck = false"), sshCommand())

        assertTrue(out.endsWith("fastcheck = false\n"))
        assertFalse(out.endsWith("\n\n"))
    }
}
