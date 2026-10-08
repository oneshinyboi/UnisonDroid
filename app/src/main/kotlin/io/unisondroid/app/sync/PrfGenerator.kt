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
