package io.unisondroid.app.sync

import io.unisondroid.app.data.ConflictPolicy
import io.unisondroid.app.data.Profile
import java.io.File

data class SshCommand(
    val binary: File,
    val configFile: File,
)

object PrfGenerator {

    fun generate(profile: Profile, ssh: SshCommand): String {
        val sb = StringBuilder()
        sb.append("root = ").append(profile.localRoot).append('\n')
        sb.append("root = ").append(sshRoot(profile)).append('\n')
        sb.append("sshcmd = ").append(ssh.binary.absolutePath).append('\n')
        sb.append("sshargs = -F ").append(ssh.configFile.absolutePath).append('\n')
        sb.append("perms = 0\n")
        sb.append("links = false\n")
        sb.append("fat = true\n")
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
        for (pref in conflictPolicyPreferences(profile)) {
            sb.append(pref).append('\n')
        }
        return sb.toString()
    }

    internal fun conflictPolicyPreferences(profile: Profile): List<String> = when (profile.conflictPolicy) {
        ConflictPolicy.SKIP -> emptyList()
        ConflictPolicy.PREFER_NEWER -> listOf("prefer = newer")
        ConflictPolicy.PREFER_OLDER -> listOf("prefer = older")
        ConflictPolicy.PREFER_LOCAL -> listOf("prefer = ${profile.localRoot}")
        ConflictPolicy.PREFER_REMOTE -> listOf("prefer = ${sshRoot(profile)}")
        ConflictPolicy.KEEP_BOTH -> listOf("prefer = newer", "copyonconflict = true")
    }

    internal fun sshRoot(profile: Profile): String =
        "ssh://${profile.user}@${profile.host}/${profile.remoteRoot}"

    private const val DEFAULT_SERVER_COMMAND = "unison"
}
