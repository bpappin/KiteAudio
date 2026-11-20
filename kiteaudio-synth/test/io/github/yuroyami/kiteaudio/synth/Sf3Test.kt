package io.github.yuroyami.kiteaudio.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Sf3Test {

    /** Sixteen bytes of sample data, standing in for an Ogg stream. */
    private val pool = ShortArray(8) { (0x6700 + it).toShort() }

    /** The same bytes as the file stores them: each 16-bit value low byte first. */
    private fun poolBytes(from: Int, until: Int): ByteArray =
        ByteArray(pool.size * 2) { i -> (pool[i / 2].toInt() shr (8 * (i % 2))).toByte() }.copyOfRange(from, until)

    /** One compressed mono sample: bytes 2 to 14 of the pool, looping over decoded frames 5 to 90. */
    private fun sf3(): ByteArray = Sf2Builder().build(
        sampleFrames = pool,
        samples = listOf(Sf2Builder.SampleSpec("ogg", 2, 14, 5, 90, 44100, 60, type = 0x11)),
    )

    @Test
    fun anSf3IsStillRefusedUnlessItsSamplesAreKept() {
        val failure = assertFailsWith<SoundFontFormatException> { KiteSynth.readFont(sf3()) }
        assertTrue(failure.message!!.contains("keepCompressed"), failure.message)
    }

    @Test
    fun keptSamplesHoldTheirBytesAndLoops() {
        val sample = KiteSynth.readFont(sf3(), keepCompressed = true).samples.single()
        assertContentEquals(poolBytes(2, 14), sample.compressed)
        assertEquals(0, sample.frameCount)
        assertEquals(5, sample.loopStart)
        assertEquals(90, sample.loopEnd)
    }

    @Test
    fun decodingFillsTheFramesAndTheFontPlays() {
        val font = KiteSynth.readFont(sf3(), keepCompressed = true).decoded { ShortArray(100) { (it * 300).toShort() } }
        val sample = font.samples.single()
        assertNull(sample.compressed)
        assertEquals(100, sample.frameCount)
        assertEquals(0, sample.type and 0x10)
        assertEquals(90, sample.loopEnd)
        val out = FloatArray(512 * 2)
        Synth(font, 48_000).apply { noteOn(0, 60, 100) }.render(out, 512)
        assertTrue(out.any { it != 0f }, "the decoded sample sounds")
    }

    @Test
    fun aSynthRefusesAFontThatIsStillCompressed() {
        val failure = assertFailsWith<IllegalArgumentException> { Synth(KiteSynth.readFont(sf3(), keepCompressed = true)) }
        assertTrue(failure.message!!.contains("decoded"), failure.message)
    }

    @Test
    fun aLoopPastTheDecodedEndIsCut() {
        val sample = KiteSynth.readFont(sf3(), keepCompressed = true).decoded { ShortArray(50) }.samples.single()
        assertEquals(50, sample.loopEnd)
    }

    @Test
    fun theWriterWritesCompressedSamplesBackAsAnSf3() {
        val font = KiteSynth.readFont(sf3(), keepCompressed = true)
        val bytes = SoundFontWriter.write(font)
        val again = KiteSynth.readFont(bytes, keepCompressed = true).samples.single()
        val sample = font.samples.single()
        assertContentEquals(sample.compressed, again.compressed)
        assertEquals(sample.loopStart, again.loopStart)
        assertEquals(sample.loopEnd, again.loopEnd)
        assertEquals(sample.type, again.type)
        // Searched as bytes: decoding binary data as text can shift where the tag seems to be.
        val tag = "ifil".encodeToByteArray()
        val ifil = (0..bytes.size - 4).first { at -> (0 until 4).all { bytes[at + it] == tag[it] } }
        assertEquals(3, bytes[ifil + 8].toInt(), "an SF3 file says version 3")
    }

    @Test
    fun aFontWithNothingCompressedDecodesToItself() {
        val font = KiteSynth.readFont(Sf2Builder().build())
        assertSame(font, font.decoded { error("nothing to decode") })
    }
}
