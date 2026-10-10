package io.unisondroid.app.sync

sealed interface SyncEvent {
    data class Progress(val fraction: Float, val label: String) : SyncEvent

    data class Conflict(val path: String) : SyncEvent

    data class FailedItem(val path: String, val message: String) : SyncEvent

    data class VersionMismatch(val detail: String) : SyncEvent

    data object Completed : SyncEvent

    data class Fatal(val message: String) : SyncEvent
}

data class SyncSummary(val transferred: Int, val failed: Int, val conflicts: Int)

/**
 * Reasons unison reports as `skipped: <path> (<reason>)` that represent a real
 * conflict rather than a benign/unresolvable skip. See Unison 2.53.8 `uitext.ml`
 * and `recon.ml`; anything else (e.g. a disabled feature) is a "problem" skip.
 */
val CONFLICT_REASONS = setOf(
    "conflicting updates",
    "atomic directory",
    "properties changed on both sides",
    "contents changed on both sides",
    "symbolic links changed on both sides",
    "skip requested",
)

class OutputParser {

    private val pending = StringBuilder()
    private var swallowLeadingLf = false
    private var conflicts = 0
    private val failedPaths = mutableSetOf<String>()
    private var failedWithoutPath = 0
    private var lastProgressCount = 0
    private var transferredFromSummary: Int? = null
    private var failedFromSummary: Int? = null

    fun feed(chunk: String): List<SyncEvent> {
        if (chunk.isEmpty()) return emptyList()
        val events = mutableListOf<SyncEvent>()
        var i = 0
        if (swallowLeadingLf && chunk[0] == '\n') i = 1
        swallowLeadingLf = false
        while (i < chunk.length) {
            when (chunk[i]) {
                '\n' -> events += takeLine()
                '\r' -> {
                    events += takeLine()
                    if (i == chunk.length - 1) {
                        swallowLeadingLf = true
                    } else if (chunk[i + 1] == '\n') {
                        i++
                    }
                }
                else -> pending.append(chunk[i])
            }
            i++
        }
        return events
    }

    fun finalize(exitCode: Int): SyncSummary {
        if (pending.isNotEmpty()) {
            parseLine(pending.toString())
            pending.setLength(0)
        }
        return SyncSummary(
            transferred = transferredFromSummary ?: lastProgressCount,
            // Real unison prints both "Failed [path]: msg" and "  failed: path" for the
            // same transient failure, so prefer the summary's own count and otherwise
            // dedupe by path. Error lines carry no path and count individually.
            failed = failedFromSummary ?: (failedPaths.size + failedWithoutPath),
            conflicts = conflicts,
        )
    }

    private fun takeLine(): List<SyncEvent> {
        val line = pending.toString()
        pending.setLength(0)
        return parseLine(line)
    }

    private fun parseLine(rawLine: String): List<SyncEvent> {
        val line = ANSI_ESCAPE.replace(rawLine, "").trim()
        if (line.isEmpty()) return emptyList()

        if (isKnownUnisonMismatch(line)) return listOf(SyncEvent.VersionMismatch(line))

        if (line.startsWith(SUMMARY_PREFIX)) {
            SUMMARY.find(line)?.let { match ->
                transferredFromSummary = match.groupValues[1].toInt()
                failedFromSummary = match.groupValues[4].toInt()
            }
            return listOf(SyncEvent.Completed)
        }

        PROGRESS.find(line)?.let { match ->
            val pct = match.groupValues[1].toInt()
            lastProgressCount = match.groupValues[2].toInt()
            return listOf(SyncEvent.Progress(pct / 100f, line))
        }

        SKIP.find(line)?.let { match ->
            val path = match.groupValues[1].trim()
            val reason = match.groupValues[2].trim()
            if (reason in CONFLICT_REASONS) {
                conflicts++
                return listOf(SyncEvent.Conflict(path = path))
            }
            return emptyList()
        }

        FAILED_FILE.find(line)?.let { match ->
            failedPaths += match.groupValues[1].trim()
            return listOf(SyncEvent.FailedItem(path = match.groupValues[1].trim(), message = ""))
        }

        FAILED_ITEM.find(line)?.let { match ->
            failedPaths += match.groupValues[1].trim()
            return listOf(
                SyncEvent.FailedItem(
                    path = match.groupValues[1].trim(),
                    message = match.groupValues[2].trim(),
                ),
            )
        }

        if (line.startsWith(ERROR_PREFIX)) {
            failedWithoutPath++
            return listOf(SyncEvent.FailedItem(path = "", message = line.substringAfter(ERROR_PREFIX).trim()))
        }

        if (line.contains("different versions", ignoreCase = true) ||
            line.contains("incompatible", ignoreCase = true)
        ) {
            return listOf(SyncEvent.VersionMismatch(line))
        }

        return emptyList()
    }

    private fun isKnownUnisonMismatch(line: String): Boolean =
        REAL_MISMATCH_MARKERS.any { line.contains(it) }

    companion object {
        private val ANSI_ESCAPE = Regex(Char(27) + "\\[[0-9;?]*[A-Za-z]")

        private val PROGRESS = Regex("""^\s*(\d{1,3})%\s+(\d+)/(\d+)\s+\(([^)]*) of ([^)]*)\)\s+.*ETA\s*$""")

        // Anchored on "Synchronization" so the earlier "N items will be synced, M skipped"
        // line is ignored. Matches both the complete and incomplete terminators; the
        // "P partially transferred, " segment is optional in real output. Captures:
        // 1 = transferred, 2 = partially transferred (optional), 3 = skipped, 4 = failed.
        private val SUMMARY =
            Regex("""Synchronization (?:complete|incomplete) at \d{2}:\d{2}:\d{2}\s+\((\d+) items? transferred, (?:(\d+) partially transferred, )?(\d+) skipped, (\d+) failed.*\)""")

        private val SKIP = Regex("""^\s*skipped:\s+(.*?)\s+\((.*)\)\s*$""")

        private val FAILED_FILE = Regex("""^\s*failed:\s+(.*)$""")

        private val FAILED_ITEM = Regex("""^Failed \[(.*?)]:\s?(.*)$""")

        private const val SUMMARY_PREFIX = "Synchronization "

        private const val ERROR_PREFIX = "Error:"

        private val REAL_MISMATCH_MARKERS = listOf(
            "Received unexpected header from the server",
            "Received unexpected header from the client",
            "None of server's RPC versions are supported",
            "Unknown server RPC version",
            "Unknown client RPC version",
            "Received error from the server",
            "Client and server are incompatible",
            "different versions of Unison",
        )
    }
}
