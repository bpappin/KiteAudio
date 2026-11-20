package io.github.yuroyami.kiteaudio.synth

/**
 * A SoundFont could not be read.
 *
 * Thrown rather than returning null so a caller cannot use half a font by accident. The message
 * says what was expected and where, because "invalid file" tells nobody anything.
 *
 * @property offset where in the bytes the trouble was, or -1 when it is not about one place.
 */
public class SoundFontFormatException(
    message: String,
    public val offset: Int = -1,
    cause: Throwable? = null,
) : Exception(if (offset >= 0) "$message (at byte $offset)" else message, cause)
