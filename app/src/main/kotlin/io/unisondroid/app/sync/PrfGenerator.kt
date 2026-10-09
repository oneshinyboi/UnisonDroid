package io.unisondroid.app.sync

import io.unisondroid.app.data.Profile

object PrfGenerator {

    fun generate(profile: Profile, localSocketPort: Int): String {
        val sb = StringBuilder()
        sb.append("root = ").append(profile.localRoot).append('\n')
        // Remote root on the server, as part of the socket root
        // (socket://host:port/path; an absolute path yields a double slash).
        sb.append("root = socket://127.0.0.1:").append(localSocketPort)
            .append('/').append(profile.remoteRoot).append('\n')
        sb.append("perms = 0\n")
        sb.append("links = false\n")
        sb.append("fat = true\n")
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
}
