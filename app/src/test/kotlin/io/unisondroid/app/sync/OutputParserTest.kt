package io.unisondroid.app.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutputParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("unison-output/$name")!!
            .readBytes().decodeToString()

    private fun feedAll(text: String, chunkSize: Int = Int.MAX_VALUE): Pair<OutputParser, List<SyncEvent>> {
        val parser = OutputParser()
        val events = mutableListOf<SyncEvent>()
        var i = 0
        while (i < text.length) {
            val end = minOf(i + chunkSize, text.length)
            events += parser.feed(text.substring(i, end))
            i = end
        }
        return parser to events
    }

    private fun feedFinalize(name: String): SyncSummary = feedAll(fixture(name)).first.finalize(0)

    @Test
    fun `progress lines emit fraction and label`() {
        val raw = " 12%   3/10  (1.2 MiB of 4.5 MiB)  250 KiB/s    00:12 ETA"
        val (parser, events) = feedAll("$raw\n")

        assertEquals(
            listOf(SyncEvent.Progress(0.12f, raw.trim())),
            events,
        )
        assertEquals(SyncSummary(3, 0, 0), parser.finalize(0))
    }

    @Test
    fun `real progress line yields overall fraction`() {
        val (_, events) = feedAll(fixture("progress.txt"))
        assertEquals(0.12f, (events[0] as SyncEvent.Progress).fraction, 0.001f)
        assertEquals(1.0f, (events[1] as SyncEvent.Progress).fraction, 0.001f)
    }

    @Test
    fun `line split across carriage return fragments is reassembled`() {
        val line1 = " 12%   3/10  (1.2 MiB of 4.5 MiB)  250 KiB/s    00:12 ETA"
        val line2 = "100%  10/10  (4.5 MiB of 4.5 MiB)  00:00:00 ETA"
        val parser = OutputParser()

        assertTrue(parser.feed(line1.substring(0, 30)).isEmpty())
        assertEquals(
            listOf(SyncEvent.Progress(0.12f, line1.trim())),
            parser.feed(line1.substring(30) + "\r" + line2.substring(0, 20)),
        )
        assertEquals(
            listOf(SyncEvent.Progress(1.0f, line2)),
            parser.feed(line2.substring(20) + "\r"),
        )
        assertEquals(SyncSummary(10, 0, 0), parser.finalize(0))
    }

    @Test
    fun `crlf split across fragments yields no stray events`() {
        val line1 = " 50%   1/2  (0.5 MiB of 1.0 MiB)  1.0 MiB/s    00:01 ETA"
        val line2 = "100%   2/2  (1.0 MiB of 1.0 MiB)  00:00:00 ETA"
        val parser = OutputParser()

        assertEquals(1, parser.feed("$line1\r").size)
        assertEquals(
            listOf(SyncEvent.Progress(1.0f, line2)),
            parser.feed("\n$line2\r\n"),
        )
    }

    @Test
    fun `line longer than 4 KiB keeps trailing events`() {
        val label = "x".repeat(5000)
        val progressLine = "100%  1/1  (1.0 MiB of 1.0 MiB)  $label  00:00:00 ETA"
        val text =
            "$progressLine\n" +
                "Synchronization complete at 21:33:33  (1 item transferred, 0 skipped, 0 failed)\n"
        val (parser, events) = feedAll(text, chunkSize = 1024)

        assertEquals(2, events.size)
        val progress = events[0] as SyncEvent.Progress
        assertEquals(1f, progress.fraction)
        assertEquals(progressLine, progress.label)
        assertEquals(SyncEvent.Completed, events[1])
        assertEquals(SyncSummary(1, 0, 0), parser.finalize(0))
    }

    @Test
    fun `happy path fixture yields expected events and totals`() {
        val (parser, events) = feedAll(fixture("happy-path.txt"))

        assertEquals(
            listOf(
                SyncEvent.Progress(0.12f, "12%   3/10  (1.2 MiB of 4.5 MiB)  250 KiB/s    00:12 ETA"),
                SyncEvent.Progress(0.5f, "50%   5/10  (2.3 MiB of 4.5 MiB)  250 KiB/s    00:09 ETA"),
                SyncEvent.Progress(1f, "100%  10/10  (4.5 MiB of 4.5 MiB)  00:00:00 ETA"),
                SyncEvent.Completed,
            ),
            events,
        )
        assertEquals(SyncSummary(transferred = 10, failed = 0, conflicts = 0), parser.finalize(0))
    }

    @Test
    fun `happy path fixture in 7-char fragments yields identical events`() {
        val text = fixture("happy-path.txt")

        val (wholeParser, wholeEvents) = feedAll(text)
        val (chunkParser, chunkEvents) = feedAll(text, chunkSize = 7)

        assertEquals(wholeEvents, chunkEvents)
        assertEquals(wholeParser.finalize(0), chunkParser.finalize(0))
    }

    @Test
    fun `conflicts and failures fixture yields expected events and totals`() {
        val (parser, events) = feedAll(fixture("conflicts-failures.txt"))

        assertEquals(
            listOf(
                SyncEvent.Conflict("notes/plan.txt"),
                SyncEvent.Conflict("photos/shared.bin"),
                SyncEvent.Conflict("music/playlist.m3u"),
                SyncEvent.FailedItem("docs/report.pdf", ""),
                SyncEvent.FailedItem("photos/raw/img.cr2", "Input/output error"),
                SyncEvent.Completed,
            ),
            events,
        )
        assertEquals(SyncSummary(transferred = 2, failed = 2, conflicts = 3), parser.finalize(0))
    }

    @Test
    fun `real unison header mismatch banner yields VersionMismatch not FailedItem`() {
        val (_, events) = feedAll(fixture("version-mismatch.txt"))

        assertEquals(
            listOf(
                SyncEvent.VersionMismatch("Error: Received unexpected header from the server:"),
                SyncEvent.VersionMismatch("This can happen because you have different versions of Unison"),
            ),
            events,
        )
    }

    @Test
    fun `real unison rpc unsupported banner yields VersionMismatch`() {
        val (_, events) = feedAll(fixture("rpc-unsupported.txt"))

        assertEquals(1, events.size)
        val mismatch = events[0] as SyncEvent.VersionMismatch
        assertTrue(mismatch.detail.contains("None of server's RPC versions are supported"))
    }

    @Test
    fun `real and loose version mismatch markers match single lines`() {
        val lines = listOf(
            "Error: Client and server are incompatible. Setting up feature \"xattrs\" failed with error \"boom\"",
            "Error: Unknown server RPC version: parse error. Version received from server: \"x\". Supported RPC versions: \"1\"",
            "Error: Received error from the server: \"NOK\".",
            "Received unexpected header from the client: \"garbage\"",
            "banner: INCOMPATIBLE unison build detected",
            "warning: server runs different versions of unison",
        )

        lines.forEach { line ->
            val (_, events) = feedAll("$line\n")

            assertEquals(1, events.size, "no event produced for: $line")
            assertTrue(events[0] is SyncEvent.VersionMismatch, "wrong event type for: $line")
        }
    }

    @Test
    fun `loose mismatch substrings in paths do not outrank specific matchers`() {
        val progressLine = " 50%   1/2  (1.0 MiB of 2.0 MiB)  docs/incompatible.md  00:01 ETA"
        val (_, progressEvents) = feedAll("$progressLine\n")
        assertEquals(
            listOf(SyncEvent.Progress(0.5f, progressLine.trim())),
            progressEvents,
        )

        val (_, conflictEvents) = feedAll("  skipped: design/different versions.txt (conflicting updates)\n")
        assertEquals(
            listOf(SyncEvent.Conflict("design/different versions.txt")),
            conflictEvents,
        )

        val (_, bannerEvents) = feedAll(
            "Error: Client and server are incompatible. Setting up feature \"xattrs\" failed with error \"boom\"\n",
        )
        assertEquals(1, bannerEvents.size)
        assertTrue(bannerEvents[0] is SyncEvent.VersionMismatch)
    }

    @Test
    fun `nonzero exit without Completed finalizes counts and appends nothing extra`() {
        val parser = OutputParser()

        assertEquals(2, parser.feed("  failed: docs/report.pdf\nError: boom\n").size)
        assertEquals(SyncSummary(transferred = 0, failed = 2, conflicts = 0), parser.finalize(1))

        val untouched = OutputParser()
        assertEquals(SyncSummary(transferred = 0, failed = 0, conflicts = 0), untouched.finalize(1))
    }

    @Test
    fun `without summary line transferred falls back to last progress item count`() {
        val parser = OutputParser()

        parser.feed(
            "  1%   1/345  (1.0 MiB of 345 MiB)  1.0 MiB/s    01:00 ETA\n" +
                " 50%   5/9  (5.0 MiB of 9.0 MiB)  1.0 MiB/s    00:05 ETA\n",
        )

        assertEquals(SyncSummary(transferred = 5, failed = 0, conflicts = 0), parser.finalize(0))
    }

    @Test
    fun `trailing unterminated line still counts at finalize`() {
        val parser = OutputParser()

        parser.feed("  skipped: notes/plan.txt (conflicting updates)")

        assertEquals(SyncSummary(transferred = 0, failed = 0, conflicts = 1), parser.finalize(1))
    }

    @Test
    fun `ansi colored real summary completes with item count`() {
        val text =
            "Synchronization \u001B[32mcomplete\u001B[0m at 21:33:33  (5 items transferred, 0 skipped, 0 failed)\n"
        val (parser, events) = feedAll(text)

        assertEquals(listOf<SyncEvent>(SyncEvent.Completed), events)
        assertEquals(SyncSummary(transferred = 5, failed = 0, conflicts = 0), parser.finalize(0))
    }

    @Test
    fun `synchronization incomplete still completes and counts from summary`() {
        val (parser, events) =
            feedAll("Synchronization incomplete at 21:33:33  (1 item transferred, 0 skipped, 3 failed)\n")

        assertEquals(listOf<SyncEvent>(SyncEvent.Completed), events)
        assertEquals(1, parser.finalize(1).transferred)
    }

    @Test
    fun `incomplete summary still finalizes counts`() {
        assertEquals(SyncSummary(transferred = 1, failed = 2, conflicts = 1), feedFinalize("incomplete.txt"))
    }

    @Test
    fun `problem skips are not counted as conflicts`() {
        assertEquals(SyncSummary(transferred = 0, failed = 0, conflicts = 1), feedFinalize("skips.txt"))
    }

    @Test
    fun `failed lines and zero-transfer complete summary finalize counts`() {
        assertEquals(SyncSummary(transferred = 0, failed = 2, conflicts = 0), feedFinalize("failures.txt"))
    }

    @Test
    fun `duplicate failure forms for the same path count once via summary`() {
        assertEquals(1, feedFinalize("failed-dedup.txt").failed)
    }

    @Test
    fun `without a summary duplicate failure paths are deduped`() {
        val (parser, _) = feedAll(
            "Failed [notes/plan.txt]: Input/output error\n" +
                "  failed: notes/plan.txt\n",
        )

        assertEquals(1, parser.finalize(1).failed)
    }

    @Test
    fun `partially transferred segment in summary is tolerated`() {
        val (parser, _) = feedAll(
            "Synchronization incomplete at 21:33:33  (2 items transferred, 1 partially transferred, 3 skipped, 4 failed)\n",
        )

        assertEquals(SyncSummary(transferred = 2, failed = 4, conflicts = 0), parser.finalize(1))
    }
}
