package io.unisondroid.app.sync

import io.unisondroid.app.data.Profile
import java.io.File

data class SshCommand(
    val binary: File,
    val keyFile: File,
    val knownHosts: File,
    val port: Int,
)

object PrfGenerator {

    fun generate(profile: Profile, ssh: SshCommand): String {
        val sb = StringBuilder()
        sb.append("root = ").append(profile.localRoot).append('\n')
        sb.append("root = ssh://").append(profile.user).append('@').append(profile.host)
            .append(profile.remoteRoot).append('\n')
        sb.append("ssh = ").append(ssh.binary.absolutePath)
            .append(" -F none")
            .append(" -i ").append(ssh.keyFile.absolutePath)
            .append(" -o UserKnownHostsFile=").append(ssh.knownHosts.absolutePath)
            .append(" -o StrictHostKeyChecking=yes")
            .append(" -o BatchMode=yes")
            .append(" -o IdentitiesOnly=yes")
            .append(" -o LogLevel=ERROR")
            .append(" -p ").append(ssh.port)
            .append('\n')
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
        return sb.toString()
    }

    private const val DEFAULT_SERVER_COMMAND = "unison"
}
