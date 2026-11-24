package io.github.yuroyami.kiteaudio.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The worklet processor's own JavaScript, run in Node with a stand-in for the worklet scope. It
 * checks what the processor writes to the speakers and what it hands back, which no test of the
 * Kotlin side can see.
 */
class WebAudioWorkletTest {

    /** A processor made from [WEB_AUDIO_WORKLET] for [channels] channels, with its posts recorded. */
    private fun processor(channels: Int): dynamic {
        val make = js("Function")("source", PROCESSOR_SCOPE)
        val create = make(WEB_AUDIO_WORKLET)
        val options: dynamic = js("({ processorOptions: {} })")
        options.processorOptions.channels = channels
        return create(options)
    }

    private fun send(processor: dynamic, slot: Int, samples: Array<Float>) {
        val message: dynamic = js("({})")
        message.slot = slot
        message.data = js("Float32Array").from(samples)
        val event: dynamic = js("({})")
        event.data = message
        processor.port.onmessage(event)
    }

    private fun outputs(channels: Int, frames: Int): dynamic {
        val planes = js("[]")
        repeat(channels) { planes.push(js("new Float32Array(frames)")) }
        return js("[planes]")
    }

    private fun plane(outputs: dynamic, channel: Int): List<Float> =
        (js("Array").from(outputs[0][channel]) as Array<Float>).toList()

    @Test
    fun interleavedBlocksArePlayedAsPlanesAcrossBlockBoundaries() {
        val p = processor(2)
        send(p, 0, arrayOf(1f, -1f, 2f, -2f, 3f, -3f))
        send(p, 1, arrayOf(4f, -4f, 5f, -5f, 6f, -6f))
        val out = outputs(2, 4)

        assertTrue(p.process(js("[]"), out) as Boolean)

        assertEquals(listOf(1f, 2f, 3f, 4f), plane(out, 0))
        assertEquals(listOf(-1f, -2f, -3f, -4f), plane(out, 1))
        assertEquals(1, p.port.posted.length as Int, "only the first block was finished")
        val first = p.port.posted[0]
        assertEquals(0, first.m.slot as Int)
        assertEquals(0, first.m.underrun as Int)
        assertEquals(first.m.data.buffer, first.t[0], "the buffer was not transferred back")
    }

    @Test
    fun silenceCountsAsUnderrunOnlyAfterTheFirstBlock() {
        val p = processor(1)
        p.process(js("[]"), outputs(1, 128))
        send(p, 3, arrayOf(0.5f, 0.5f))
        val out = outputs(1, 8)
        p.process(js("[]"), out)

        assertEquals(listOf(0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f), plane(out, 0))
        assertEquals(0, p.port.posted[0].m.underrun as Int, "the wait before the first block was counted")

        p.process(js("[]"), outputs(1, 8))
        send(p, 3, arrayOf(0.25f, 0.25f))
        p.process(js("[]"), outputs(1, 2))
        assertEquals(14, p.port.posted[1].m.underrun as Int)
    }

    @Test
    fun stopEndsTheProcessor() {
        val p = processor(2)
        val stop: dynamic = js("({ data: { stop: true } })")
        p.port.onmessage(stop)
        assertFalse(p.process(js("[]"), outputs(2, 128)) as Boolean)
    }
}

/** Loads a processor's source the way a worklet scope would, and returns a way to make one. */
private const val PROCESSOR_SCOPE: String = """
const posted = [];
let registered = null;
class AudioWorkletProcessor {
  constructor() { this.port = { posted: posted, postMessage(m, t) { posted.push({ m: m, t: t }); } }; }
}
new Function("AudioWorkletProcessor", "registerProcessor", source)(AudioWorkletProcessor, (name, type) => { registered = type; });
return (options) => new registered(options);
"""
