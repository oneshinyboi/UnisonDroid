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

    @Test
    fun `progress lines emit fraction and label`() {
        val (parser, events) = feedAll("[wnt] ...  12/345 KiB  photos/vacation.jpg\n")

        assertEquals(
            listOf(SyncEvent.Progress(12f / 345f, "photos/vacation.jpg")),
            events,
        )
        assertEquals(SyncSummary(0, 0, 0), parser.finalize(0))
    }

    @Test
    fun `line split across carriage return fragments is reassembled`() {
        val parser = OutputParser()

        assertTrue(parser.feed("[wnt] ...  12/345 KiB  pho").isEmpty())
        assertEquals(
            listOf(SyncEvent.Progress(12f / 345f, "photos/vacation.jpg")),
            parser.feed("tos/vacation.jpg\r[wnt] ...  345/345 KiB  photos/vacat"),
        )
        assertEquals(
            listOf(SyncEvent.Progress(1f, "photos/vacation.jpg")),
            parser.feed("ion.jpg\r"),
        )
        assertEquals(SyncSummary(1, 0, 0), parser.finalize(0))
    }

    @Test
    fun `crlf split across fragments yields no stray events`() {
        val parser = OutputParser()

        assertEquals(1, parser.feed("[wnt] ...  1/2 KiB  a.txt\r").size)
        assertEquals(
            listOf(SyncEvent.Progress(1f, "a.txt")),
            parser.feed("\n[wnt] ...  2/2 KiB  a.txt\r\n"),
        )
    }

    @Test
    fun `line longer than 4 KiB keeps trailing events`() {
        val label = "x".repeat(5000)
        val text =
            "[wnt] ...  1/1 KiB  $label\n" +
                "Synchronization complete ... 1 files ... 1 KiB transferred\n"
        val (parser, events) = feedAll(text, chunkSize = 1024)

        assertEquals(2, events.size)
        val progress = events[0] as SyncEvent.Progress
        assertEquals(1f, progress.fraction)
        assertEquals(label, progress.label)
        assertEquals(SyncEvent.Completed, events[1])
        assertEquals(SyncSummary(1, 0, 0), parser.finalize(0))
    }

    @Test
    fun `happy path fixture yields expected events and totals`() {
        val (parser, events) = feedAll(fixture("happy-path.txt"))

        assertEquals(
            listOf(
                SyncEvent.Progress(0f, "photos/vacation.jpg"),
                SyncEvent.Progress(12f / 345f, "photos/vacation.jpg"),
                SyncEvent.Progress(1f, "photos/vacation.jpg"),
                SyncEvent.Progress(1f, "notes/todo.txt"),
                SyncEvent.Progress(0f, "video/clip.mp4"),
                SyncEvent.Progress(0.5f, "video/clip.mp4"),
                SyncEvent.Progress(1f, "video/clip.mp4"),
                SyncEvent.Progress(1f, "config/settings.json"),
                SyncEvent.Completed,
            ),
            events,
        )
        assertEquals(SyncSummary(transferred = 4, failed = 0, conflicts = 0), parser.finalize(0))
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
                SyncEvent.FailedItem("", "Input/output error [read()/write()]"),
                SyncEvent.Completed,
            ),
            events,
        )
        assertEquals(SyncSummary(transferred = 2, failed = 3, conflicts = 3), parser.finalize(0))
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
    fun `nonzero exit without Completed finalizes counts and appends nothing extra`() {
        val parser = OutputParser()

        assertEquals(2, parser.feed("[FAILED] docs/report.pdf\nError: boom\n").size)
        assertEquals(SyncSummary(transferred = 0, failed = 2, conflicts = 0), parser.finalize(1))

        val untouched = OutputParser()
        assertEquals(SyncSummary(transferred = 0, failed = 0, conflicts = 0), untouched.finalize(1))
    }

    @Test
    fun `without summary line transferred falls back to completed progress count`() {
        val parser = OutputParser()

        parser.feed(
            "[wnt] ...  1/345 KiB  a.txt\n" +
                "[wnt] ...  345/345 KiB  a.txt\n" +
                "[wnt] ...  0/9 KiB  b.txt\n",
        )

        assertEquals(SyncSummary(transferred = 1, failed = 0, conflicts = 0), parser.finalize(0))
    }

    @Test
    fun `trailing unterminated line still counts at finalize`() {
        val parser = OutputParser()

        parser.feed("[CONFLICT] notes/plan.txt")

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
    fun `synchronization incomplete is not Completed`() {
        val (_, events) =
            feedAll("Synchronization incomplete at 21:33:33  (1 item transferred, 0 skipped, 3 failed)\n")

        assertTrue(events.isEmpty())
    }
}
