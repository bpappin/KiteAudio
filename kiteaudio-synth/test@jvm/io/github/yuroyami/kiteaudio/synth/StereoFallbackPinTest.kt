package io.github.yuroyami.kiteaudio.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the exact JVM output of fonts that have no playable stereo pair.
 *
 * The hashes come from the renderer that played every zone as its own voice. A font without a
 * valid pair must still sound like that, bit for bit. Kotlin/JS keeps a Float in double precision,
 * so these exact numbers hold on the JVM only.
 */
class StereoFallbackPinTest {

    private val pinned = mapOf(
        "a link past the last sample" to -4079610649859032922L,
        "a link that is not returned" to -4079610649859032922L,
        "two left samples" to -4079610649859032922L,
        "a partner zone off the key" to 8977604903451718981L,
        "a partner zone off the velocity" to 8977604903451718981L,
        "a partner in another instrument" to -4079610649859032922L,
        "mono samples" to -4079610649859032922L,
    )

    @Test
    fun aFontWithoutAPlayablePairSoundsExactlyAsBefore() {
        assertEquals(pinned.keys, StereoFonts.fallbacks.keys)
        for ((name, case) in StereoFonts.fallbacks) {
            val (left, right, split) = case
            val hash = StereoFonts.hash(StereoFonts.perform(StereoFonts.font(left, right, split)))
            assertEquals(pinned.getValue(name), hash, "$name no longer sounds as it did")
        }
    }

    @Test
    fun aValidPairWithDifferentPitchesSoundsDifferentFromBefore() {
        // The same zones as "mono samples", but linked: the left side now takes the right side's pitch.
        val (left, right) = StereoFonts.validPair
        val hash = StereoFonts.hash(StereoFonts.perform(StereoFonts.font(left, right)))
        assertNotEquals(-4079610649859032922L, hash)
    }
}
