package io.github.yuroyami.kiteaudio.midi.internal

/** The largest value a variable-length quantity can hold: four bytes of seven bits each. */
internal const val MAX_VAR_LEN: Int = 0x0FFFFFFF

/**
 * Appends [value] as a variable-length quantity: seven bits per byte, most significant first,
 * with the top bit set on every byte except the last.
 */
internal fun MutableList<Byte>.writeVarLen(value: Int) {
    require(value >= 0) { "a variable-length quantity cannot be negative, got $value" }
    require(value <= MAX_VAR_LEN) { "a variable-length quantity tops out at $MAX_VAR_LEN, got $value" }

    var buffer = value.toLong() and 0x7F
    var rest = value ushr 7
    while (rest > 0) {
        buffer = (buffer shl 8) or ((rest and 0x7F).toLong() or 0x80L)
        rest = rest ushr 7
    }
    while (true) {
        add((buffer and 0xFF).toByte())
        if (buffer and 0x80L != 0L) buffer = buffer shr 8 else return
    }
}
