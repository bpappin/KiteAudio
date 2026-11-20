package io.github.yuroyami.kiteaudio.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** The render step every sink shares: what it reports for a fast, a slow, a short and a failing block. */
class RenderAudioBlockTest {
    private val events = mutableListOf<AudioSinkEvent>()

    private fun render(deadlineMicros: Long, frames: Int = 4, callback: AudioRenderCallback): Boolean =
        renderAudioBlock(callback, FloatArray(frames * 2), frames, deadlineMicros) { events += it }

    /** Busy-waits, because common code has no sleep. */
    private fun spin(micros: Long) {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow().inWholeMicroseconds < micros) {
            // wait
        }
    }

    @Test
    fun aFastFullBlockReportsNothing() {
        assertTrue(render(deadlineMicros = 10_000_000L) { _, frames -> frames })
        assertEquals(emptyList(), events)
    }

    @Test
    fun aSlowFullBlockReportsAnOverrunAndNoUnderrun() {
        assertTrue(render(deadlineMicros = 1_000L) { _, frames -> spin(3_000L); frames })
        val overrun = events.single() as AudioSinkEvent.Overrun
        assertEquals(1_000L, overrun.deadlineMicros)
        assertTrue(overrun.tookMicros >= 3_000L, "took ${overrun.tookMicros} microseconds")
    }

    @Test
    fun aSlowShortBlockReportsTheOverrunBeforeTheUnderrun() {
        assertTrue(render(deadlineMicros = 1_000L) { _, frames -> spin(3_000L); frames - 1 })
        assertEquals(2, events.size)
        assertTrue(events[0] is AudioSinkEvent.Overrun)
        assertEquals(AudioSinkEvent.Underrun(1), events[1])
    }

    @Test
    fun aThrowingCallbackFailsWithoutAnOverrun() {
        val problem = IllegalStateException("boom")
        assertFalse(render(deadlineMicros = 0L) { _, _ -> throw problem })
        assertEquals(listOf<AudioSinkEvent>(AudioSinkEvent.Failed("the render callback threw", problem)), events)
    }

    @Test
    fun anImpossibleCountFailsWithoutAnOverrun() {
        assertFalse(render(deadlineMicros = 0L) { _, frames -> frames + 1 })
        assertTrue(events.single() is AudioSinkEvent.Failed)
    }
}
