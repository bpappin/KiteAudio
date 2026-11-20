package io.github.yuroyami.kitesynth

import io.github.yuroyami.kitesynth.internal.readSoundFont

/** The way in. */
public object KiteSynth {

    /**
     * Reads a SoundFont 2 file.
     *
     * @param keepCompressed read an SF3 font's compressed samples as they are, into
     *   [SampleHeader.compressed], instead of refusing the font. [SoundFont.decoded] turns them into frames.
     * Sample rates must be positive and fit the public signed 32-bit representation. Invalid
     * rates are rejected rather than guessed; valid high rates such as 96 kHz remain supported.
     * @throws SoundFontFormatException when the bytes are not a readable font. Nothing is
     *   returned half-read: either the whole font is there, or this throws.
     */
    @Throws(SoundFontFormatException::class)
    public fun readFont(bytes: ByteArray, keepCompressed: Boolean = false): SoundFont =
        readSoundFont(bytes, keepCompressed, DEFAULT_SAMPLE_BYTES)

    /**
     * Reads a font with an explicit cumulative sample payload budget. Every sample header's
     * retained PCM or compressed bytes count, including overlapping ranges and unused samples.
     * The budget is checked before any sample payload is copied. The ordinary overload allows
     * 64 MiB; callers loading larger trusted fonts can choose a larger limit here.
     *
     * @throws SoundFontFormatException if the font is malformed or exceeds [maxSampleBytes].
     * @throws IllegalArgumentException if [maxSampleBytes] is negative.
     */
    @Throws(SoundFontFormatException::class, IllegalArgumentException::class)
    public fun readFont(bytes: ByteArray, keepCompressed: Boolean = false, maxSampleBytes: Long): SoundFont =
        readSoundFont(bytes, keepCompressed, maxSampleBytes)
}

internal const val DEFAULT_SAMPLE_BYTES: Long = 64L * 1024 * 1024
