package io.github.yuroyami.kiteaudio.core

/**
 * A stream of interleaved Float32 PCM.
 *
 * The stream is pull based, synchronous, and free of any assumption about where
 * the samples come from. A decoder, an array in memory or a generator all
 * satisfy it.
 *
 * Reading consumes the stream. Create a fresh source to read the samples again.
 */
public interface PcmSource {

    /** Sample rate and channel layout of the samples this produces. */
    public val format: AudioFormat

    /**
     * Frames this will produce in total, or null when that is not known in
     * advance.
     *
     * Knowing it lets an encoder write its length fields as it goes, which is
     * what makes encoding to a sink that cannot seek backwards possible.
     */
    public val frameCount: Long?

    /**
     * Fills [into] with up to [frames] frames of interleaved samples starting
     * at [offset], and returns how many frames were written.
     *
     * A return of 0 means end of stream. A short return that is not 0 is
     * allowed and does not mean end of stream.
     */
    public fun read(into: FloatArray, offset: Int, frames: Int): Int
}
