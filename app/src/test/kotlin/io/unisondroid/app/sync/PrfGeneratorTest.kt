package io.unisondroid.app.sync

import io.unisondroid.app.data.ConflictPolicy
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
        policy: ConflictPolicy = ConflictPolicy.SKIP,
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
        conflictPolicy = policy,
    )

    private fun sshCommand() = SshCommand(
        binary = File("/data/app/lib/arm64/libssh.so"),
        configFile = File("/data/data/io.unisondroid.app/no_backup/ssh/ssh_config"),
    )

    @Test
    fun `roots and ssh line come first in order`() {
        val out = PrfGenerator.generate(profile(), sshCommand())
        val lines = out.lines()

        assertEquals("root = /storage/emulated/0/Documents", lines[0])
        assertEquals("root = ssh://diamond@server.example//srv/sync", lines[1])
        assertEquals("sshcmd = /data/app/lib/arm64/libssh.so", lines[2])
        assertEquals(
            "sshargs = -F /data/data/io.unisondroid.app/no_backup/ssh/ssh_config",
            lines[3],
        )
        assertEquals(
            listOf("perms = 0", "links = false", "fat = true", "ignorecase = false"),
            lines.subList(4, 8),
        )
    }

    @Test
    fun `ignorecase false follows fat to keep internal storage case sensitive`() {
        val lines = PrfGenerator.generate(profile(), sshCommand()).lines()

        // `fat = true` implies ignorecase = true; the explicit line after it
        // overrides that so case-sensitive storage (ext4/f2fs) stays in
        // case-sensitive mode. Order matters: the override must come after.
        val fat = lines.indexOf("fat = true")
        assertEquals(fat + 1, lines.indexOf("ignorecase = false"), "got:\n${lines.joinToString("\n")}")
    }

    @Test
    fun `removable root turns ignorecase on after fat`() {
        val lines = PrfGenerator.generate(profile(), sshCommand(), caseInsensitive = true).lines()

        val fat = lines.indexOf("fat = true")
        assertEquals(fat + 1, lines.indexOf("ignorecase = true"), "got:\n${lines.joinToString("\n")}")
        assertTrue(
            lines.none { it == "ignorecase = false" },
            "a removable root must not also emit ignorecase = false; got:\n${lines.joinToString("\n")}",
        )
    }

    @Test
    fun `advanced prefs can restore case-insensitivity`() {
        val out = PrfGenerator.generate(profile(advancedPrefs = "ignorecase = true\n"), sshCommand())
        val lines = out.lines()

        val defaultIndex = lines.indexOf("ignorecase = false")
        val advancedIndex = lines.indexOf("ignorecase = true")
        assertTrue(defaultIndex >= 0, "default ignorecase=false missing; got:\n$out")
        assertTrue(defaultIndex < advancedIndex, "advanced prefs must be able to override the default; got:\n$out")
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
        assertTrue(out.contains("root = ssh://diamond@server.example//home/Diamond/my folder ♥\n"), "got:\n$out")
    }

    @Test
    fun `port never appears in the prf`() {
        val out = PrfGenerator.generate(profile(sshPort = 2222), sshCommand())

        assertFalse(
            out.contains("2222"),
            "the port now lives in the ssh_config, got:\n$out",
        )
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
            "root = ssh://diamond@server.example//srv/sync\n" +
            "sshcmd = /data/app/lib/arm64/libssh.so\n" +
            "sshargs = -F /data/data/io.unisondroid.app/no_backup/ssh/ssh_config\n" +
            "perms = 0\n" +
            "links = false\n" +
            "fat = true\n" +
            "ignorecase = false\n"

        assertEquals(expected, PrfGenerator.generate(profile(), sshCommand()))
    }

    @Test
    fun `advanced without trailing newline still ends with single newline`() {
        val out = PrfGenerator.generate(profile(advancedPrefs = "fastcheck = false"), sshCommand())

        assertTrue(out.endsWith("fastcheck = false\n"))
        assertFalse(out.endsWith("\n\n"))
    }

    @Test
    fun `skip policy writes no conflict prefs`() {
        val out = PrfGenerator.generate(profile(), sshCommand())

        assertFalse(out.contains("prefer"), "SKIP must emit nothing, got:\n$out")
        assertFalse(out.contains("copyonconflict"), "SKIP must emit nothing, got:\n$out")
        assertFalse(out.contains("times ="), "SKIP must not touch times, got:\n$out")
    }

    @Test
    fun `prefer newer writes prefer newer and enables times`() {
        val lines = PrfGenerator.generate(profile(policy = ConflictPolicy.PREFER_NEWER), sshCommand()).lines()

        assertTrue(lines.contains("prefer = newer"), "got:\n${lines.joinToString("\n")}")
        assertTrue(lines.contains("times = true"), "mtime policy must enable times; got:\n${lines.joinToString("\n")}")
    }

    @Test
    fun `prefer older writes prefer older and enables times`() {
        val lines = PrfGenerator.generate(profile(policy = ConflictPolicy.PREFER_OLDER), sshCommand()).lines()

        assertTrue(lines.contains("prefer = older"), "got:\n${lines.joinToString("\n")}")
        assertTrue(lines.contains("times = true"), "mtime policy must enable times; got:\n${lines.joinToString("\n")}")
    }

    @Test
    fun `mtime policy overrides an explicit times false by coming last`() {
        val lines = PrfGenerator.generate(
            profile(policy = ConflictPolicy.PREFER_OLDER, advancedPrefs = "times = false\n"),
            sshCommand(),
        ).lines()

        val explicitFalse = lines.indexOf("times = false")
        val forcedTrue = lines.indexOf("times = true")
        assertTrue(
            explicitFalse >= 0,
            "expected the user's times=false to still be present; got:\n${lines.joinToString("\n")}",
        )
        assertTrue(
            forcedTrue > explicitFalse,
            "times=true must come after times=false so the policy works; got:\n${lines.joinToString("\n")}",
        )
    }

    @Test
    fun `policies that do not use mtimes leave times untouched`() {
        for (policy in listOf(ConflictPolicy.SKIP, ConflictPolicy.PREFER_LOCAL, ConflictPolicy.PREFER_REMOTE)) {
            val out = PrfGenerator.generate(profile(policy = policy), sshCommand())
            assertFalse(out.contains("times ="), "$policy must not set times; got:\n$out")
        }
    }

    @Test
    fun `prefer local writes the local root`() {
        val lines = PrfGenerator.generate(profile(policy = ConflictPolicy.PREFER_LOCAL), sshCommand()).lines()

        assertTrue(
            lines.contains("prefer = /storage/emulated/0/Documents"),
            "got:\n${lines.joinToString("\n")}",
        )
    }

    @Test
    fun `prefer remote names the ssh root exactly`() {
        val lines = PrfGenerator.generate(profile(policy = ConflictPolicy.PREFER_REMOTE), sshCommand()).lines()

        assertTrue(
            lines.contains("prefer = ssh://diamond@server.example//srv/sync"),
            "got:\n${lines.joinToString("\n")}",
        )
    }

    @Test
    fun `keep both prefers newer and copies on conflict`() {
        val lines = PrfGenerator.generate(profile(policy = ConflictPolicy.KEEP_BOTH), sshCommand()).lines()

        assertTrue(lines.contains("prefer = newer"), "got:\n${lines.joinToString("\n")}")
        assertTrue(lines.contains("copyonconflict = true"), "got:\n${lines.joinToString("\n")}")
        assertTrue(lines.contains("times = true"), "mtime policy must enable times; got:\n${lines.joinToString("\n")}")
    }

    @Test
    fun `conflict prefs come after the advanced block`() {
        val out = PrfGenerator.generate(
            profile(advancedPrefs = "fastcheck = false\n", policy = ConflictPolicy.PREFER_NEWER),
            sshCommand(),
        )

        assertTrue(out.indexOf("fastcheck = false") < out.indexOf("prefer = newer"), "got:\n$out")
    }

    @Test
    fun `one way run suppresses the profile conflict policy`() {
        // A forced mirror must not also emit `prefer`/`copyonconflict`: with `force` in play the
        // policy is meaningless, and `copyonconflict` would litter the target with copies.
        val lines = PrfGenerator.generate(
            profile(policy = ConflictPolicy.KEEP_BOTH),
            sshCommand(),
            extraPrefs = listOf("force = /storage/emulated/0/Documents"),
            includeConflictPolicy = false,
        ).lines()

        assertTrue(lines.contains("force = /storage/emulated/0/Documents"), "got:\n${lines.joinToString("\n")}")
        assertFalse(lines.contains("prefer = newer"), "got:\n${lines.joinToString("\n")}")
        assertFalse(lines.contains("copyonconflict = true"), "got:\n${lines.joinToString("\n")}")
        assertFalse(lines.contains("times = true"), "a one-way mirror must not start syncing times; got:\n${lines.joinToString("\n")}")
    }
}
