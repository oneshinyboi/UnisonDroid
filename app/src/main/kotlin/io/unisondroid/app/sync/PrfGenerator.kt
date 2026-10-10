package io.unisondroid.app.sync

import io.unisondroid.app.data.ConflictPolicy
import io.unisondroid.app.data.Profile
import java.io.File

data class SshCommand(
    val binary: File,
    val configFile: File,
)

object PrfGenerator {

    fun generate(
        profile: Profile,
        ssh: SshCommand,
        extraPrefs: List<String> = emptyList(),
        caseInsensitive: Boolean = false,
        includeConflictPolicy: Boolean = true,
    ): String {
        val sb = StringBuilder()
        sb.append("root = ").append(profile.localRoot).append('\n')
        sb.append("root = ").append(sshRoot(profile)).append('\n')
        sb.append("sshcmd = ").append(ssh.binary.absolutePath).append('\n')
        sb.append("sshargs = -F ").append(ssh.configFile.absolutePath).append('\n')
        sb.append("perms = 0\n")
        sb.append("links = false\n")
        // `fat = true` implies `ignorecase = true`. That is correct for a
        // removable volume (typically FAT/exFAT), but Android's internal
        // storage (ext4/f2fs) is case sensitive: left case-insensitive, Unison
        // aborts when two names differ only in case ("...cannot be synchronized
        // to a file system being treated as case-insensitive"). Emit the case
        // mode explicitly, after `fat`, so it overrides the implied default.
        // Advanced prefs are appended later and can still override this.
        sb.append("fat = true\n")
        sb.append("ignorecase = ").append(caseInsensitive).append('\n')
        if (profile.serverCommand.isNotEmpty() && profile.serverCommand != DEFAULT_SERVER_COMMAND) {
            sb.append("servercmd = ").append(profile.serverCommand).append('\n')
        }
        // Unison locks each archive so two clients can't corrupt it. Its Unix
        // lock uses a hard link (src/lock.ml), which Android's SELinux policy
        // denies for untrusted_app on app_data_file. native/build-unison.sh
        // patches the bundled binary to use its O_EXCL lock branch instead
        // (safe here: Android storage is not NFS), so real locking is restored.
        // The app additionally serializes its own syncs (SyncEngine.syncMutex +
        // foreground service) and clears stale lock files before each run.
        for (pattern in profile.ignorePatterns) {
            sb.append("ignore = Path ").append(pattern).append('\n')
        }
        if (profile.advancedPrefs.isNotEmpty()) {
            sb.append(profile.advancedPrefs)
            if (!profile.advancedPrefs.endsWith('\n')) {
                sb.append('\n')
            }
        }
        val policyPrefs = if (includeConflictPolicy) conflictPolicyPreferences(profile) else emptyList()
        for (pref in dedupeScalarPreferences(policyPrefs + extraPrefs)) {
            sb.append(pref).append('\n')
        }
        return sb.toString()
    }

    internal fun conflictPolicyPreferences(profile: Profile): List<String> = when (profile.conflictPolicy) {
        ConflictPolicy.SKIP -> emptyList()
        ConflictPolicy.PREFER_NEWER -> listOf("prefer = newer")
        // `prefer = older` requires synced modtimes; if the user disabled them in the
        // advanced prefs, Unison aborts the whole run, so fall back to skipping the
        // conflict (the safe default) instead of failing the sync.
        ConflictPolicy.PREFER_OLDER -> if (timesDisabled(profile)) emptyList() else listOf("prefer = older")
        ConflictPolicy.PREFER_LOCAL -> listOf("prefer = ${profile.localRoot}")
        ConflictPolicy.PREFER_REMOTE -> listOf("prefer = ${sshRoot(profile)}")
        ConflictPolicy.KEEP_BOTH -> listOf("prefer = newer", "copyonconflict = true")
    }

    private fun timesDisabled(profile: Profile): Boolean =
        TIMES_FALSE.containsMatchIn(profile.advancedPrefs)

    // A scalar pref such as `copyonconflict = true` must be emitted at most once,
    // even when both the profile's policy and a per-file decision request it.
    // `preferpartial` lines are repeatable (one per resolved path): keep them all.
    private fun dedupeScalarPreferences(prefs: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return prefs.filter { it.startsWith(PREFER_PARTIAL_PREFIX) || seen.add(it) }
    }

    private const val PREFER_PARTIAL_PREFIX = "preferpartial"

    // A `times = false` line (any spacing) in the advanced prefs disables modtime syncing.
    // Only the profile's literal advancedPrefs are inspected; a `times = false` in a
    // sourced/included pref file would not be detected (the app never writes those).
    private val TIMES_FALSE = Regex("""(?m)^\s*times\s*=\s*false\s*$""")

    internal fun sshRoot(profile: Profile): String =
        "ssh://${profile.user}@${profile.host}/${profile.remoteRoot}"

    private const val DEFAULT_SERVER_COMMAND = "unison"
}
