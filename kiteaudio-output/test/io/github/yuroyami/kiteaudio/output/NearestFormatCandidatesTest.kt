package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The order in which [AudioFormatPolicy.Nearest] tries formats. */
class NearestFormatCandidatesTest {

    private fun stereo(rate: Int) = AudioFormat(rate, ChannelLayout.STEREO)
    private fun mono(rate: Int) = AudioFormat(rate, ChannelLayout.MONO)

    @Test
    fun withNoReportedRatesTheDefaultListIsOrderedByDistanceThenTheOtherChannelCount() {
        val rates = listOf(48_000, 44_100, 32_000, 22_050, 16_000, 8_000, 88_200, 96_000)
        assertEquals(
            rates.map(::stereo) + rates.map(::mono),
            nearestFormatCandidates(stereo(48_000), emptyList()),
        )
    }

    @Test
    fun theRequestedFormatComesFirstEvenWhenTheDeviceDoesNotReportItsRate() {
        assertEquals(
            listOf(stereo(50_000), stereo(48_000), stereo(44_100), mono(50_000), mono(48_000), mono(44_100)),
            nearestFormatCandidates(stereo(50_000), listOf(44_100, 48_000)),
        )
    }

    @Test
    fun aTieGoesToTheHigherRate() {
        assertEquals(
            listOf(mono(46_000), mono(48_000), mono(44_000), stereo(46_000), stereo(48_000), stereo(44_000)),
            nearestFormatCandidates(mono(46_000), listOf(44_000, 48_000)),
        )
    }

    @Test
    fun reportedRatesReplaceTheDefaultListAndRepeatsAndNonsenseAreDropped() {
        assertEquals(
            listOf(stereo(44_100), stereo(48_000), stereo(96_000), mono(44_100), mono(48_000), mono(96_000)),
            nearestFormatCandidates(stereo(44_100), listOf(96_000, 0, -1, 48_000, 48_000, 44_100)),
        )
    }

    @Test
    fun theRequestedLayoutIsKeptAndTheOtherCountUsesTheCanonicalLayout() {
        val unpositioned = AudioFormat(48_000, ChannelLayout.unknown(2))
        val candidates = nearestFormatCandidates(unpositioned, listOf(44_100))
        assertEquals(listOf(unpositioned, AudioFormat(44_100, ChannelLayout.unknown(2)), mono(48_000), mono(44_100)), candidates)
    }

    @Test
    fun exactTriesOnlyTheRequestedFormat() {
        val tried = mutableListOf<AudioFormat>()
        assertNull(openFirstFormat<Unit>(stereo(48_000), AudioFormatPolicy.Exact, emptyList()) { tried += it; null })
        assertEquals(listOf(stereo(48_000)), tried)
    }

    @Test
    fun nearestStopsAtTheFirstFormatThatOpens() {
        val tried = mutableListOf<AudioFormat>()
        val opened = openFirstFormat(stereo(48_000), AudioFormatPolicy.Nearest, listOf(44_100, 96_000)) {
            tried += it
            if (it == stereo(96_000)) it else null
        }
        assertEquals(stereo(96_000), opened)
        assertEquals(listOf(stereo(48_000), stereo(44_100), stereo(96_000)), tried)
    }
}
