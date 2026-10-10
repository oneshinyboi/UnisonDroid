package io.unisondroid.app.sync

import io.unisondroid.app.data.ConflictRecord
import io.unisondroid.app.data.FailedRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
        assertEquals(SyncSummary(3, emptyList(), emptyList()), parser.finalize(0))
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
        assertEquals(SyncSummary(10, emptyList(), emptyList()), parser.finalize(0))
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
        assertEquals(SyncSummary(1, emptyList(), emptyList()), parser.finalize(0))
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
        assertEquals(
            SyncSummary(transferred = 10, conflicts = emptyList(), failed = emptyList()),
            parser.finalize(0),
        )
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

        assertEquals(listOf<SyncEvent>(SyncEvent.Completed), events)
        assertEquals(
            SyncSummary(
                transferred = 2,
                conflicts = listOf(
                    ConflictRecord("notes/plan.txt", "conflicting updates"),
                    ConflictRecord("photos/shared.bin", "properties changed on both sides"),
                    ConflictRecord("music/playlist.m3u", "contents changed on both sides"),
                ),
                failed = listOf(
                    FailedRecord("docs/report.pdf", ""),
                    FailedRecord("photos/raw/img.cr2", "Input/output error"),
                ),
            ),
            parser.finalize(0),
        )
    }

    @Test
    fun `real unison header mismatch banner yields VersionMismatch`() {
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

        val (conflictParser, conflictEvents) =
            feedAll("  skipped: design/different versions.txt (conflicting updates)\n")
        assertTrue(conflictEvents.isEmpty(), "a skip line must not be reported as a version mismatch")
        assertEquals(
            listOf(ConflictRecord("design/different versions.txt", "conflicting updates")),
            conflictParser.finalize(0).conflicts,
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

        assertTrue(parser.feed("  failed: docs/report.pdf\nError: boom\n").isEmpty())
        assertEquals(
            SyncSummary(
                transferred = 0,
                conflicts = emptyList(),
                failed = listOf(FailedRecord("docs/report.pdf", ""), FailedRecord("", "boom")),
            ),
            parser.finalize(1),
        )

        val untouched = OutputParser()
        assertEquals(SyncSummary(0, emptyList(), emptyList()), untouched.finalize(1))
    }

    @Test
    fun `without summary line transferred falls back to last progress item count`() {
        val parser = OutputParser()

        parser.feed(
            "  1%   1/345  (1.0 MiB of 345 MiB)  1.0 MiB/s    01:00 ETA\n" +
                " 50%   5/9  (5.0 MiB of 9.0 MiB)  1.0 MiB/s    00:05 ETA\n",
        )

        assertEquals(
            SyncSummary(transferred = 5, conflicts = emptyList(), failed = emptyList()),
            parser.finalize(0),
        )
    }

    @Test
    fun `trailing unterminated line still counts at finalize`() {
        val parser = OutputParser()

        parser.feed("  skipped: notes/plan.txt (conflicting updates)")

        assertEquals(
            SyncSummary(
                transferred = 0,
                conflicts = listOf(ConflictRecord("notes/plan.txt", "conflicting updates")),
                failed = emptyList(),
            ),
            parser.finalize(1),
        )
    }

    @Test
    fun `ansi colored real summary completes with item count`() {
        val text =
            "Synchronization \u001B[32mcomplete\u001B[0m at 21:33:33  (5 items transferred, 0 skipped, 0 failed)\n"
        val (parser, events) = feedAll(text)

        assertEquals(listOf<SyncEvent>(SyncEvent.Completed), events)
        assertEquals(
            SyncSummary(transferred = 5, conflicts = emptyList(), failed = emptyList()),
            parser.finalize(0),
        )
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
        assertEquals(
            SyncSummary(
                transferred = 1,
                conflicts = listOf(
                    ConflictRecord("notes/plan.txt", "conflicting updates"),
                    ConflictRecord("broken/", "Syncing symbolic links is disabled", resolvable = false),
                ),
                failed = listOf(
                    FailedRecord("docs/report.pdf", ""),
                    FailedRecord("photos/raw/img.cr2", "Input/output error"),
                ),
            ),
            feedFinalize("incomplete.txt"),
        )
    }

    @Test
    fun `skip line becomes a resolvable conflict record`() {
        val s = feedFinalize("skips.txt")
        assertEquals(
            listOf(ConflictRecord("notes/plan.txt", "conflicting updates", resolvable = true)),
            s.conflicts.filter { it.resolvable },
        )
        assertTrue(s.conflicts.any { !it.resolvable }, "the symbolic-link problem must be recorded as unresolvable")
    }

    @Test
    fun `display block fills both sides`() {
        val c = feedFinalize("display-block.txt").conflicts.single()

        assertEquals("notes/plan.txt", c.path)
        assertEquals(1234L, c.local?.sizeBytes)
        assertEquals(5678L, c.remote?.sizeBytes)
        assertNotNull(c.local?.modifiedAt)
        assertNotNull(c.remote?.modifiedAt)
        assertEquals("conflicting updates", c.reason)
    }

    @Test
    fun `display block split by carriage returns and one-char chunks yields the same record`() {
        val text = fixture("display-block.txt").replace("\n", "\r")

        val whole = feedAll(text).first.finalize(0).conflicts.single()
        val chunked = feedAll(text, chunkSize = 1).first.finalize(0).conflicts.single()

        assertEquals(whole, chunked)
        assertEquals(1234L, chunked.local?.sizeBytes)
        assertEquals(5678L, chunked.remote?.sizeBytes)
        assertEquals("conflicting updates", chunked.reason)
    }

    @Test
    fun `missing display block still yields the conflict with null sides`() {
        val c = feedFinalize("skips.txt").conflicts.first { it.path == "notes/plan.txt" }

        assertNull(c.local)
        assertNull(c.remote)
        assertEquals("conflicting updates", c.reason)
    }

    @Test
    fun `display block path with spaces keeps the full path`() {
        val text =
            "changed  <-?-> changed    My Folder/file.txt\n" +
                "local        : changed file       modified on 2024-01-02 at  3:04:05  size 42    -rw-r--r--\n" +
                "host         : changed file       modified on 2024-01-02 at  4:05:06  size 99    -rw-r--r--\n" +
                "\n" +
                "  skipped: My Folder/file.txt (conflicting updates)\n"
        val c = feedAll(text).first.finalize(0).conflicts.single()

        assertEquals("My Folder/file.txt", c.path)
        assertEquals(42L, c.local?.sizeBytes)
        assertEquals(99L, c.remote?.sizeBytes)
    }

    @Test
    fun `consecutive display blocks produce one record each and do not cross-attach`() {
        val text =
            "changed  <-?-> changed    a.txt\n" +
                "local        : changed file       modified on 2024-01-02 at  3:04:05  size 1     -rw-r--r--\n" +
                "host         : changed file       modified on 2024-01-02 at  4:05:06  size 2     -rw-r--r--\n" +
                "  skipped: a.txt (conflicting updates)\n" +
                "changed  <=?=> changed    b.txt\n" +
                "local        : changed file       modified on 2024-01-02 at  3:04:05  size 3     -rw-r--r--\n" +
                "host         : changed file       modified on 2024-01-02 at  4:05:06  size 4     -rw-r--r--\n" +
                "  skipped: b.txt (conflicting updates)\n"
        val conflicts = feedAll(text).first.finalize(0).conflicts

        assertEquals(listOf("a.txt", "b.txt"), conflicts.map { it.path })
        assertEquals(1L, conflicts[0].local?.sizeBytes)
        assertEquals(2L, conflicts[0].remote?.sizeBytes)
        assertEquals(3L, conflicts[1].local?.sizeBytes)
        assertEquals(4L, conflicts[1].remote?.sizeBytes)
    }

    @Test
    fun `moved conflict merges sides and reason onto one record`() {
        val conflicts = feedFinalize("moved-conflict.txt").conflicts

        assertEquals(1, conflicts.size)
        val c = conflicts.single()
        assertEquals("newdir/thing.txt", c.path)
        assertEquals(1234L, c.local?.sizeBytes)
        assertEquals(5678L, c.remote?.sizeBytes)
        assertEquals("conflicting updates", c.reason)
    }

    @Test
    fun `moved conflict with an overwrite warning still merges onto one record`() {
        val text =
            "changed  <-?-> changed    newdir/thing.txt (--> new name: olddir/thing.txt)" +
                " <will overwrite a file in the other replica>\n" +
                "local        : changed file       modified on 2024-01-02 at  3:04:05  size 11    -rw-r--r--\n" +
                "host         : changed file       modified on 2024-01-02 at  4:05:06  size 22    -rw-r--r--\n" +
                "\n" +
                "  skipped: newdir/thing.txt (conflicting updates)\n"
        val conflicts = feedAll(text).first.finalize(0).conflicts

        assertEquals(1, conflicts.size)
        val c = conflicts.single()
        assertEquals("newdir/thing.txt", c.path)
        assertEquals(11L, c.local?.sizeBytes)
        assertEquals(22L, c.remote?.sizeBytes)
        assertEquals("conflicting updates", c.reason)
    }

    @Test
    fun `path with a literal parenthesis and no move suffix is untouched`() {
        val text =
            "changed  <-?-> changed    notes (draft).txt\n" +
                "local        : changed file       modified on 2024-01-02 at  3:04:05  size 7     -rw-r--r--\n" +
                "host         : changed file       modified on 2024-01-02 at  4:05:06  size 8     -rw-r--r--\n" +
                "\n"
        val c = feedAll(text).first.finalize(0).conflicts.single()

        assertEquals("notes (draft).txt", c.path)
        assertEquals(7L, c.local?.sizeBytes)
        assertEquals(8L, c.remote?.sizeBytes)
    }

    @Test
    fun `skipped path containing a parenthesis splits at the last one`() {
        val noBlock = feedAll(
            "  skipped: report (1).txt (conflicting updates)\n",
        ).first.finalize(0).conflicts.single()

        assertEquals("report (1).txt", noBlock.path)
        assertEquals("conflicting updates", noBlock.reason)
        assertTrue(noBlock.resolvable)

        val withBlock = feedAll(
            "changed  <-?-> changed    report (1).txt\n" +
                "local        : changed file       modified on 2024-01-02 at  3:04:05  size 42    -rw-r--r--\n" +
                "host         : changed file       modified on 2024-01-02 at  4:05:06  size 99    -rw-r--r--\n" +
                "\n" +
                "  skipped: report (1).txt (conflicting updates)\n",
        ).first.finalize(0).conflicts.single()

        assertEquals("report (1).txt", withBlock.path)
        assertEquals("conflicting updates", withBlock.reason)
        assertEquals(42L, withBlock.local?.sizeBytes)
        assertEquals(99L, withBlock.remote?.sizeBytes)
    }

    @Test
    fun `failed lines and zero-transfer complete summary finalize counts`() {
        assertEquals(
            SyncSummary(
                transferred = 0,
                conflicts = emptyList(),
                failed = listOf(
                    FailedRecord("docs/report.pdf", ""),
                    FailedRecord("photos/raw/img.cr2", "Input/output error"),
                ),
            ),
            feedFinalize("failures.txt"),
        )
    }

    @Test
    fun `duplicate failure forms for the same path count once via summary`() {
        assertEquals(
            listOf(FailedRecord("notes/plan.txt", "Input/output error")),
            feedFinalize("failed-dedup.txt").failed,
        )
    }

    @Test
    fun `without a summary duplicate failure paths are deduped`() {
        val (parser, _) = feedAll(
            "Failed [notes/plan.txt]: Input/output error\n" +
                "  failed: notes/plan.txt\n",
        )

        assertEquals(
            listOf(FailedRecord("notes/plan.txt", "Input/output error")),
            parser.finalize(1).failed,
        )
    }

    @Test
    fun `partially transferred segment in summary is tolerated`() {
        val (parser, _) = feedAll(
            "  partially transferred: docs/report.pdf\n" +
                "Synchronization incomplete at 21:33:33  (2 items transferred, 1 partially transferred, 3 skipped, 4 failed)\n",
        )

        val summary = parser.finalize(1)
        assertEquals(2, summary.transferred)
        // The summary's partial count has no field of its own, but each per-file line
        // is captured as a failure record so the partial is not silently dropped.
        assertEquals(
            listOf(FailedRecord("docs/report.pdf", "partially transferred")),
            summary.failed,
        )
        assertTrue(summary.conflicts.isEmpty())
    }
}
