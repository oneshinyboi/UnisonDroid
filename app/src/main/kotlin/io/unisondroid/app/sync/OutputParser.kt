package io.unisondroid.app.sync

import io.unisondroid.app.data.ConflictRecord
import io.unisondroid.app.data.FailedRecord
import io.unisondroid.app.data.SideInfo
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

sealed interface SyncEvent {
    data class Progress(val fraction: Float, val label: String) : SyncEvent

    data class VersionMismatch(val detail: String) : SyncEvent

    data object Completed : SyncEvent

    data class Fatal(val message: String) : SyncEvent
}

data class SyncSummary(
    val transferred: Int,
    val conflicts: List<ConflictRecord>,
    val failed: List<FailedRecord>,
)

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
    private val conflictsByPath = LinkedHashMap<String, ConflictRecord>()
    private val failedByPath = LinkedHashMap<String, FailedRecord>()
    private val failedWithoutPath = mutableListOf<FailedRecord>()
    private var lastProgressCount = 0
    private var transferredFromSummary: Int? = null

    // The conflict display block: a recon line (`<-?->`/`<=?=>`) sets the path, then
    // up to two detail lines follow (first side = local, second = remote). A blank
    // line or the next recon line closes the block.
    private var pendingDisplayPath: String? = null
    private var pendingSideIndex = 0

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
            conflicts = conflictsByPath.values.toList(),
            // Real unison prints both "Failed [path]: msg" and "  failed: path" for the
            // same transient failure, so dedupe path-bearing entries by path. Error lines
            // carry no path and are reported individually.
            failed = failedByPath.values.toList() + failedWithoutPath,
        )
    }

    private fun takeLine(): List<SyncEvent> {
        val line = pending.toString()
        pending.setLength(0)
        return parseLine(line)
    }

    private fun parseLine(rawLine: String): List<SyncEvent> {
        val line = ANSI_ESCAPE.replace(rawLine, "").trim()
        if (line.isEmpty()) {
            closeDisplayBlock()
            return emptyList()
        }

        if (isKnownUnisonMismatch(line)) return listOf(SyncEvent.VersionMismatch(line))

        if (line.startsWith(SUMMARY_PREFIX)) {
            SUMMARY.find(line)?.let { match ->
                transferredFromSummary = match.groupValues[1].toInt()
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
            // Problem skips (not a conflict reason) are still surfaced, but marked
            // unresolvable so the resolver can leave them alone.
            conflictsByPath[path] = (conflictsByPath[path] ?: ConflictRecord(path = path)).copy(
                reason = reason,
                resolvable = reason in CONFLICT_REASONS,
            )
            return emptyList()
        }

        FAILED_FILE.find(line)?.let { match ->
            recordFailure(path = match.groupValues[1].trim(), message = "")
            return emptyList()
        }

        FAILED_ITEM.find(line)?.let { match ->
            recordFailure(path = match.groupValues[1].trim(), message = match.groupValues[2].trim())
            return emptyList()
        }

        if (line.startsWith(ERROR_PREFIX)) {
            failedWithoutPath += FailedRecord(path = "", message = line.substringAfter(ERROR_PREFIX).trim())
            return emptyList()
        }

        // Recon line (fixed-width replica columns + 5-char action + full path).
        RECON_LINE.find(line)?.let { match ->
            closeDisplayBlock()
            val action = match.groupValues[2]
            if (action == CONFLICT_ACTION || action == CONFLICT_ACTION_FORCED) {
                pendingDisplayPath = match.groupValues[4]
                pendingSideIndex = 0
            }
            return emptyList()
        }

        // Detail line, only meaningful while a conflict block is open. The `\s+:` guard
        // keeps `skipped:`/`failed:` lines out (they have no space before the colon).
        pendingDisplayPath?.let { displayPath ->
            DETAIL_LINE.find(line)?.let { match ->
                val rest = match.groupValues[2]
                if (DETAIL_SIZE.containsMatchIn(rest)) {
                    applyDisplaySide(displayPath, rest)
                    return emptyList()
                }
            }
        }

        if (line.contains("different versions", ignoreCase = true) ||
            line.contains("incompatible", ignoreCase = true)
        ) {
            return listOf(SyncEvent.VersionMismatch(line))
        }

        return emptyList()
    }

    // Dedupe failure records by path: `Failed [path]: msg` and `  failed: path` can
    // both describe the same transient failure. Keep the first record, but upgrade it
    // if a later line carries a message the first one lacked.
    private fun recordFailure(path: String, message: String) {
        val existing = failedByPath[path]
        if (existing == null || (existing.message.isBlank() && message.isNotBlank())) {
            failedByPath[path] = FailedRecord(path = path, message = message)
        }
    }

    private fun closeDisplayBlock() {
        pendingDisplayPath = null
        pendingSideIndex = 0
    }

    // Sides are written into the path-keyed map as soon as they are seen, so the
    // record exists even before the later `skipped:` line supplies the reason; the
    // skip handler then merges that in without dropping the sides.
    private fun applyDisplaySide(path: String, rest: String) {
        val size = DETAIL_SIZE.find(rest)?.groupValues?.get(1)?.toLongOrNull()
        val timeText = DETAIL_TIME.find(rest)?.groupValues?.get(1)
        val kind = rest.substringBefore("modified on").trim().ifBlank { null }
        val side = SideInfo(sizeBytes = size, modifiedAt = parseSideTime(timeText), kind = kind)
        val existing = conflictsByPath[path] ?: ConflictRecord(path = path)
        conflictsByPath[path] =
            if (pendingSideIndex == 0) existing.copy(local = side) else existing.copy(remote = side)
        pendingSideIndex++
    }

    // Unison's `Time.toString` is `%4d-%02d-%02d at %2d:%.2d:%.2d`, so the hour is
    // space-padded (`... at  3:04:05`). Collapse the whitespace and parse leniently,
    // leaving the timestamp null rather than throwing on an unexpected format.
    private fun parseSideTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim().replace(WHITESPACE_RUN, " ")
        return try {
            LocalDateTime.parse(normalized, SIDE_TIME_FORMATTER)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
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

        // `displayri` recon line: two fixed 8-char replica columns, a 5-char action,
        // then the full relative path (`uitext.ml:419`).
        private val RECON_LINE = Regex("""^(.{8}) (error|[-<=>?M]{5}) (.{8}) {3}(.*?)\s*$""")

        // `details2string`: `<root padded to 12> : <status>  <props>` (`uicommon.ml:306`).
        // The `\s+:` requires whitespace before the colon, so `skipped:`/`failed:` do not match.
        private val DETAIL_LINE = Regex("""^(local|\S.*?)\s+:\s+(.*)$""")

        // `Props.toString`: `modified on <time>  size <n> <perms...>` (`props.ml:1413`).
        private val DETAIL_SIZE = Regex("""\bsize\s+(\d+)""")

        private val DETAIL_TIME = Regex("""modified on (.+?)\s\s+size\b""")

        private val WHITESPACE_RUN = Regex("""\s+""")

        private val SIDE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' H:mm:ss")

        private const val CONFLICT_ACTION = "<-?->"

        private const val CONFLICT_ACTION_FORCED = "<=?=>"

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
