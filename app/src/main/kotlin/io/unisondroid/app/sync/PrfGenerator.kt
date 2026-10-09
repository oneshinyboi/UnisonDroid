package io.unisondroid.app.sync

import io.unisondroid.app.data.Profile

object PrfGenerator {

    fun generate(profile: Profile, localSocketPort: Int): String {
        val sb = StringBuilder()
        sb.append("root = ").append(profile.localRoot).append('\n')
        sb.append("root = socket://127.0.0.1:").append(localSocketPort).append('\n')
        sb.append("perms = 0\n")
        sb.append("links = false\n")
        sb.append("fat = true\n")
        // Unison locks archives by hard-linking a pid-suffixed temp file into
        // place (src/lock.ml) and treats link(2) failure as "already locked".
        // Android's SELinux policy denies { link } for untrusted_app on
        // app_data_file (confirmed via avc audit), and every app-writable path
        // is either app_data_file or FUSE-backed external storage (no hard
        // links), so the lock can never be acquired. ignorelocks disables
        // archive locking on BOTH replicas. The app serializes its own syncs
        // (SyncEngine.syncMutex + foreground service) but cannot stop another
        // client (desktop unison, another profile) from syncing the same server
        // roots at the same time; concurrent cross-client sync to the same
        // roots is unsupported in v1.
        sb.append("ignorelocks = true\n")
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
