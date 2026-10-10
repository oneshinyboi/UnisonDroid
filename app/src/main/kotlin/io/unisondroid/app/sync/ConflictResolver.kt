package io.unisondroid.app.sync

import io.unisondroid.app.data.Profile

/** How the user chose to resolve a single conflicting path. */
enum class Resolution { KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH, SKIP }

object ConflictResolver {

    /**
     * Turns per-path conflict decisions into Unison preference lines.
     *
     * `preferpartial = <pathspec> -> <root>` resolves conflicts for the paths
     * matched by the pathspec, leaving the rest of the profile's policy intact.
     * Unison compiles the `Path` predicate with `Rx.globx`, so glob
     * metacharacters in a conflict path must be escaped to match it literally.
     */
    fun resolutionPreferences(profile: Profile, decisions: Map<String, Resolution>): List<String> {
        val lines = mutableListOf<String>()
        var keepBoth = false
        for ((path, resolution) in decisions) {
            val escaped = escapeGlob(path)
            when (resolution) {
                Resolution.KEEP_LOCAL ->
                    lines += "preferpartial = Path $escaped -> ${profile.localRoot}"
                Resolution.KEEP_REMOTE ->
                    lines += "preferpartial = Path $escaped -> ${PrfGenerator.sshRoot(profile)}"
                Resolution.KEEP_BOTH -> {
                    keepBoth = true
                    lines += "preferpartial = Path $escaped -> newer"
                }
                Resolution.SKIP -> Unit
            }
        }
        if (keepBoth) {
            lines += "copyonconflict = true"
        }
        return lines
    }

    /**
     * Escapes the glob metacharacters that Unison's `Rx.globx` recognises.
     * Each is prefixed with `\`; because every character is visited exactly
     * once, a literal `\` becomes `\\` without double-escaping the output.
     */
    private fun escapeGlob(path: String): String {
        val sb = StringBuilder(path.length)
        for (ch in path) {
            if (ch in GLOB_METACHARACTERS) {
                sb.append('\\')
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    private val GLOB_METACHARACTERS = setOf('\\', '*', '?', '[', ']', '{', '}', ',')
}
