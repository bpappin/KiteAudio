package io.github.yuroyami.kitemidi

/**
 * The one failure type this library throws for malformed input.
 *
 * Every reader path ends here rather than in an index or null-pointer error, so a caller can
 * catch one type and show the user which file was bad and why.
 *
 * @param offset byte offset where the problem was found, or -1 when it is not tied to one.
 */
public class MidiFormatException(
    message: String,
    public val offset: Int = -1,
) : IllegalArgumentException(if (offset >= 0) "$message (at byte $offset)" else message)
