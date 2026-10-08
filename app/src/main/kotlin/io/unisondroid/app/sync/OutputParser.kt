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

class OutputParser {

    private val pending = StringBuilder()
    private var swallowLeadingLf = false
    private var conflicts = 0
    private var failed = 0
    private var completedTransfers = 0
    private var transferredFromSummary: Int? = null

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
            transferred = transferredFromSummary ?: completedTransfers,
            failed = failed,
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
        val progress = PROGRESS.find(line)
        val event: SyncEvent = when {
            isKnownUnisonMismatch(line) -> SyncEvent.VersionMismatch(line)

            line.startsWith(SUMMARY_PREFIX) -> {
                countTransferred(line)
                SyncEvent.Completed
            }

            progress != null -> {
                val done = progress.groupValues[1].toInt()
                val total = progress.groupValues[2].toInt()
                val fraction = when {
                    total > 0 -> done.toFloat() / total
                    done > 0 -> 1f
                    else -> 0f
                }
                if (fraction >= 1f) completedTransfers++
                SyncEvent.Progress(fraction, progress.groupValues[3].trim())
            }

            line.contains(FAILED_MARKER) -> {
                val rest = line.substringAfter(FAILED_MARKER).trim()
                val separator = rest.indexOf(": ")
                if (separator > 0) {
                    SyncEvent.FailedItem(
                        path = rest.substring(0, separator).trim(),
                        message = rest.substring(separator + 2).trim(),
                    )
                } else {
                    SyncEvent.FailedItem(path = rest, message = "")
                }
            }

            line.startsWith(ERROR_PREFIX) ->
                SyncEvent.FailedItem(path = "", message = line.substringAfter(ERROR_PREFIX).trim())

            line.contains(CONFLICT_MARKER) ->
                SyncEvent.Conflict(path = line.substringAfter(CONFLICT_MARKER).trim())

            CONFLICT_LINE.find(line) != null ->
                SyncEvent.Conflict(path = CONFLICT_LINE.find(line)!!.groupValues[1].trim())

            line.contains("conflict", ignoreCase = true) -> SyncEvent.Conflict(path = line)

            line.contains("different versions", ignoreCase = true) ||
                line.contains("incompatible", ignoreCase = true) -> SyncEvent.VersionMismatch(line)

            else -> return emptyList()
        }
        return listOf(event).also {
            when (event) {
                is SyncEvent.Conflict -> conflicts++
                is SyncEvent.FailedItem -> failed++
                else -> {}
            }
        }
    }

    private fun isKnownUnisonMismatch(line: String): Boolean =
        REAL_MISMATCH_MARKERS.any { line.contains(it) }

    private fun countTransferred(line: String) {
        val fromItems = ITEMS_TRANSFERRED.find(line)?.groupValues?.get(1)?.toInt()
        val fromFiles = FILES_TRANSFERRED.find(line)?.groupValues?.get(1)?.toInt()
        val count = fromItems ?: fromFiles
        if (count != null) transferredFromSummary = count
    }

    companion object {
        private val ANSI_ESCAPE = Regex(Char(27) + "\\[[0-9;?]*[A-Za-z]")

        private val PROGRESS = Regex("^\\[[A-Za-z]+]\\s+\\.\\.\\.\\s+(\\d+)/(\\d+)\\s+KiB\\s+(.*)$")

        private val ITEMS_TRANSFERRED = Regex("(\\d+)\\s+items?\\s+transferred")

        private val FILES_TRANSFERRED = Regex("(\\d+)\\s+files?\\b")

        private const val SUMMARY_PREFIX = "Synchronization complete"

        private const val FAILED_MARKER = "[FAILED]"

        private const val ERROR_PREFIX = "Error:"

        private const val CONFLICT_MARKER = "[CONFLICT]"

        private val CONFLICT_LINE = Regex("(?i)^conflict:\\s*(.+)$")

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
