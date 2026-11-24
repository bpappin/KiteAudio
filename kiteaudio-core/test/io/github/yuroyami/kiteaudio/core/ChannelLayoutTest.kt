package io.github.yuroyami.kiteaudio.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelLayoutTest {

    @Test
    fun ofKeepsTheInterleavingOrder() {
        val layout = ChannelLayout.of(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT)
        assertEquals(listOf(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT), layout.channels)
        assertEquals(2, layout.count)
        assertEquals(layout, ChannelLayout.of(listOf(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT)))
    }

    @Test
    fun ofCopiesTheList() {
        val channels = mutableListOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT)
        val layout = ChannelLayout.of(channels)
        channels.add(AudioChannel.FRONT_CENTER)
        assertEquals(ChannelLayout.STEREO, layout)
    }

    @Test
    fun ofRejectsImpossibleShapes() {
        assertFailsWith<IllegalArgumentException> { ChannelLayout.of() }
        assertFailsWith<IllegalArgumentException> { ChannelLayout.of(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_LEFT)
        }
    }

    @Test
    fun ofAllowsUnknownPositionsMoreThanOnce() {
        val layout = ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.UNKNOWN, AudioChannel.UNKNOWN)
        assertEquals(3, layout.count)
        assertFalse(layout.isPositional)
    }

    @Test
    fun aLayoutHoldsAtMost4096Channels() {
        assertEquals(4096, ChannelLayout.unknown(4096).count)
        assertEquals(4096, ChannelLayout.of(List(4096) { AudioChannel.UNKNOWN }).count)
        assertEquals(4096, ChannelLayout.fromWaveMask(0, 4096).count)
        assertFailsWith<IllegalArgumentException> { ChannelLayout.unknown(4097) }
        assertFailsWith<IllegalArgumentException> { ChannelLayout.of(List(4097) { AudioChannel.UNKNOWN }) }
        assertFailsWith<IllegalArgumentException> { ChannelLayout.fromWaveMask(0, 4097) }
        assertFailsWith<IllegalArgumentException> { ChannelLayout.canonical(4097) }
    }

    @Test
    fun unknownGivesOnlyUnknownPositions() {
        val layout = ChannelLayout.unknown(3)
        assertEquals(List(3) { AudioChannel.UNKNOWN }, layout.channels)
        assertFalse(layout.isPositional)
        assertEquals(0, layout.waveMask)
        assertFailsWith<IllegalArgumentException> { ChannelLayout.unknown(0) }
        assertFailsWith<IllegalArgumentException> { ChannelLayout.unknown(-1) }
    }

    @Test
    fun canonicalFollowsTheWavDefaultAssignment() {
        assertSame(ChannelLayout.MONO, ChannelLayout.canonical(1))
        assertSame(ChannelLayout.STEREO, ChannelLayout.canonical(2))
        assertSame(ChannelLayout.SURROUND_3_0, ChannelLayout.canonical(3))
        assertSame(ChannelLayout.QUAD, ChannelLayout.canonical(4))
        assertSame(ChannelLayout.SURROUND_5_0, ChannelLayout.canonical(5))
        assertSame(ChannelLayout.SURROUND_5_1, ChannelLayout.canonical(6))
        assertSame(ChannelLayout.SURROUND_7_1, ChannelLayout.canonical(8))
    }

    @Test
    fun canonicalHasNoConventionForSevenOrMoreThanEightChannels() {
        assertEquals(ChannelLayout.unknown(7), ChannelLayout.canonical(7))
        assertEquals(ChannelLayout.unknown(9), ChannelLayout.canonical(9))
        assertFailsWith<IllegalArgumentException> { ChannelLayout.canonical(0) }
    }

    @Test
    fun fromWaveMaskReadsTheBitsFromTheLowBitUpwards() {
        // 0x60F is front left, front right, front center, LFE, side left and side right.
        val layout = ChannelLayout.fromWaveMask(0x60F, 6)
        assertEquals(
            listOf(
                AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
                AudioChannel.LOW_FREQUENCY, AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT,
            ),
            layout.channels,
        )
        assertEquals(0x60F, layout.waveMask)
    }

    @Test
    fun fromWaveMaskFillsMissingChannelsWithUnknown() {
        val layout = ChannelLayout.fromWaveMask(0x3, 4)
        assertEquals(
            listOf(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.UNKNOWN, AudioChannel.UNKNOWN),
            layout.channels,
        )
    }

    @Test
    fun fromWaveMaskIgnoresBitsPastTheChannelCount() {
        assertEquals(ChannelLayout.STEREO, ChannelLayout.fromWaveMask(0x3F, 2))
    }

    @Test
    fun fromWaveMaskWithAZeroMaskIsCanonical() {
        assertSame(ChannelLayout.SURROUND_5_1, ChannelLayout.fromWaveMask(0, 6))
    }

    @Test
    fun fromWaveMaskRejectsAnImpossibleCount() {
        assertFailsWith<IllegalArgumentException> { ChannelLayout.fromWaveMask(0, -1) }
        assertFailsWith<IllegalArgumentException> { ChannelLayout.fromWaveMask(0x3, 0) }
    }

    @Test
    fun waveMaskIsTheBitsOfThePositions() {
        assertEquals(0x4, ChannelLayout.MONO.waveMask)
        assertEquals(0x3, ChannelLayout.STEREO.waveMask)
        assertEquals(0x3F, ChannelLayout.SURROUND_5_1.waveMask)
        assertEquals(0x63F, ChannelLayout.SURROUND_7_1.waveMask)
        // The mask holds no order, so a reordered layout has the same mask.
        assertEquals(0x3, ChannelLayout.of(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT).waveMask)
    }

    @Test
    fun waveMaskIsZeroWhenAnyPositionIsUnknown() {
        assertEquals(0, ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.UNKNOWN).waveMask)
    }

    @Test
    fun isPositionalIsTrueOnlyWithoutUnknownPositions() {
        assertTrue(ChannelLayout.STEREO.isPositional)
        assertTrue(ChannelLayout.SURROUND_7_1.isPositional)
        assertFalse(ChannelLayout.of(AudioChannel.UNKNOWN).isPositional)
        assertFalse(ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.UNKNOWN).isPositional)
    }

    @Test
    fun layoutsAreEqualOnlyWithTheSameChannelsInTheSameOrder() {
        val stereo = ChannelLayout.of(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT)
        assertEquals(ChannelLayout.STEREO, stereo)
        assertEquals(ChannelLayout.STEREO.hashCode(), stereo.hashCode())
        assertNotEquals(ChannelLayout.STEREO, ChannelLayout.of(AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_LEFT))
        assertNotEquals(ChannelLayout.STEREO, ChannelLayout.unknown(2))
    }

    @Test
    fun toStringListsTheChannelsInOrder() {
        assertEquals("ChannelLayout(FRONT_LEFT, FRONT_RIGHT)", ChannelLayout.STEREO.toString())
    }

    @Test
    fun waveMaskBitFollowsTheEntryOrder() {
        assertEquals(0x1, AudioChannel.FRONT_LEFT.waveMaskBit)
        assertEquals(0x2, AudioChannel.FRONT_RIGHT.waveMaskBit)
        assertEquals(0x4, AudioChannel.FRONT_CENTER.waveMaskBit)
        assertEquals(0x8, AudioChannel.LOW_FREQUENCY.waveMaskBit)
        assertEquals(0x400, AudioChannel.SIDE_RIGHT.waveMaskBit)
        assertEquals(0x20000, AudioChannel.TOP_BACK_RIGHT.waveMaskBit)
        assertEquals(0, AudioChannel.UNKNOWN.waveMaskBit)
    }
}
