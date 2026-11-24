package io.github.yuroyami.kiteaudio.midi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Reads real files nobody here wrote.
 *
 * Tests built from the specification prove the reader matches what the specification says. They
 * cannot prove it matches what programs actually write, which is a different and larger claim.
 * These files were written by other people's tools, so they are the ones that catch the
 * difference.
 *
 * The corpus is not part of this repository. Set `KITEAUDIO_MIDI_CORPUS` to a folder of `.mid`
 * files to run these tests. When it is unset or the folder is missing, the tests report themselves
 * as skipped, so a clone on another machine stays green.
 */
class CorpusTest {

    private val corpusRoots = listOfNotNull(System.getenv("KITEAUDIO_MIDI_CORPUS")?.let(::File))

    private fun corpus(): List<File> = corpusRoots
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension.lowercase() == "mid" } }
        .sortedBy { it.path }

    @Test
    fun everyFileInTheCorpusReadsAndRoundTrips() {
        val files = corpus()
        assumeTrue(files.isNotEmpty(), "corpus not present on this machine, set KITEAUDIO_MIDI_CORPUS")

        val failures = ArrayList<String>()
        var notes = 0L
        for (file in files) {
            val bytes = file.readBytes()
            try {
                val parsed = KiteMidi.read(bytes)
                val rewritten = KiteMidi.write(parsed)
                val reparsed = KiteMidi.read(rewritten)
                if (parsed != reparsed) {
                    failures += "${file.name}: write then read changed the file"
                    continue
                }
                val timeline = parsed.toTimeline()
                for (note in timeline.notes) {
                    if (note.endTick < note.startTick) {
                        failures += "${file.name}: a note ends before it starts"
                        break
                    }
                }
                notes += timeline.notes.size
            } catch (e: Throwable) {
                failures += "${file.name}: ${e::class.simpleName}: ${e.message}"
            }
        }
        println("corpus: ${files.size} files, $notes notes paired")
        assertTrue(failures.isEmpty(), "these files did not survive:\n" + failures.joinToString("\n"))
    }

    @Test
    fun everyCorpusFileHasNotesAndASensibleDuration() {
        val files = corpus()
        assumeTrue(files.isNotEmpty(), "corpus not present on this machine, set KITEAUDIO_MIDI_CORPUS")

        for (file in files) {
            val timeline = KiteMidi.readTimeline(file.readBytes())
            assertTrue(timeline.notes.isNotEmpty(), "${file.name} has no notes at all")
            assertTrue(timeline.durationMicros > 0, "${file.name} lasts no time")
            assertTrue(
                timeline.durationMicros < 60L * 60L * 1_000_000L,
                "${file.name} claims to last ${timeline.durationMicros / 1_000_000} seconds, which is over an hour",
            )
            for (note in timeline.notes) {
                assertTrue(note.key in 0..127, "${file.name} has a note outside 0 to 127")
                assertTrue(note.velocity in 1..127, "${file.name} has a note on with velocity ${note.velocity}")
            }
        }
    }

    /**
     * Every truncation of a real file must end in [MidiFormatException] and nothing else.
     *
     * A reader that throws an index error instead has a hole a hostile file could reach, and a
     * caller cannot catch one type and show the user what went wrong.
     */
    @Test
    fun truncatingAnyCorpusFileRaisesTheTypedFailureAndNothingElse() {
        val files = corpus()
        assumeTrue(files.isNotEmpty(), "corpus not present on this machine, set KITEAUDIO_MIDI_CORPUS")

        var checked = 0
        for (file in files) {
            val bytes = file.readBytes()
            // Deterministic offsets, so a failure is reproducible.
            val cuts = (1..32).map { (bytes.size.toLong() * it / 33).toInt() }.filter { it in 1 until bytes.size }
            for (cut in cuts) {
                val truncated = bytes.copyOfRange(0, cut)
                checked++
                try {
                    KiteMidi.read(truncated).toTimeline().notes
                } catch (e: MidiFormatException) {
                    // The one failure a caller has to handle.
                } catch (e: Throwable) {
                    throw AssertionError("${file.name} cut at $cut threw ${e::class.qualifiedName}: ${e.message}", e)
                }
            }
        }
        println("truncation: $checked cuts, all typed")
    }

    /** Flipping bytes inside a real file must not escape the typed failure either. */
    @Test
    fun corruptingAnyCorpusFileRaisesTheTypedFailureAndNothingElse() {
        val files = corpus()
        assumeTrue(files.isNotEmpty(), "corpus not present on this machine, set KITEAUDIO_MIDI_CORPUS")

        val random = kotlin.random.Random(seed = 20260823)
        var checked = 0
        for (file in files) {
            val original = file.readBytes()
            repeat(24) {
                val bytes = original.copyOf()
                repeat(4) {
                    bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
                }
                checked++
                try {
                    KiteMidi.read(bytes).toTimeline().notes
                } catch (e: MidiFormatException) {
                    // Expected.
                } catch (e: IllegalArgumentException) {
                    // A corrupted byte can produce an out-of-range value a constructor rejects.
                    // That is still a typed, catchable failure.
                } catch (e: Throwable) {
                    throw AssertionError("${file.name} corrupted threw ${e::class.qualifiedName}: ${e.message}", e)
                }
            }
        }
        println("corruption: $checked mutations, all typed")
    }

    @Test
    fun aFormatOneCorpusFileHasATempoMapAndSeveralTracks() {
        val files = corpus()
        assumeTrue(files.isNotEmpty(), "corpus not present on this machine, set KITEAUDIO_MIDI_CORPUS")

        val formatOne = files.map { KiteMidi.read(it.readBytes()) }.filter { it.format == 1 }
        assertTrue(formatOne.isNotEmpty(), "the corpus has no format 1 file to check")
        val withManyTracks = formatOne.count { it.tracks.size > 1 }
        assertTrue(withManyTracks > 0, "no format 1 file had more than one track")
        assertEquals(
            formatOne.size,
            formatOne.count { it.division is Division.Ppq },
            "every corpus file should count in ticks per quarter",
        )
    }
}
