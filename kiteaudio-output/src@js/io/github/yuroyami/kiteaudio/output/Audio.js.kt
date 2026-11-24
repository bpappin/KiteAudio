package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat

/** Web Audio through Kotlin/JS `dynamic`. A handle is the JavaScript object itself. */
internal object JsWebAudio : WebAudioApi<Any> {

    override fun hasAudioContext(): Boolean = js("typeof AudioContext !== 'undefined'").unsafeCast<Boolean>()

    override fun hasWorklet(): Boolean = js("typeof AudioWorkletNode !== 'undefined'").unsafeCast<Boolean>()

    override fun newContext(sampleRate: Int): Any =
        js("new AudioContext({ sampleRate: sampleRate, latencyHint: 'interactive' })").unsafeCast<Any>()

    override fun baseLatencySeconds(context: Any): Double {
        val base = context.asDynamic().baseLatency
        return if (jsTypeOf(base) == "number") base.unsafeCast<Double>() else 0.0
    }

    override fun currentTime(context: Any): Double = context.asDynamic().currentTime.unsafeCast<Double>()

    override fun loadProcessor(context: Any, source: String, done: () -> Unit, failed: (String) -> Unit) {
        try {
            val url = js("URL.createObjectURL(new Blob([source], { type: 'application/javascript' }))")
            context.asDynamic().audioWorklet.addModule(url).then(
                { _: dynamic -> js("URL").revokeObjectURL(url); done() },
                { problem: dynamic -> js("URL").revokeObjectURL(url); failed(problem.toString().unsafeCast<String>()) },
            )
        } catch (problem: Throwable) {
            failed(problem.toString())
        }
    }

    override fun onStateChange(context: Any, changed: (String) -> Unit) {
        context.asDynamic().onstatechange = { _: dynamic -> changed(context.asDynamic().state.unsafeCast<String>()) }
    }

    override fun resume(context: Any, done: () -> Unit, failed: (String) -> Unit) {
        context.asDynamic().resume().then(
            { _: dynamic -> done() },
            { problem: dynamic -> failed(problem.toString().unsafeCast<String>()) },
        )
    }

    override fun suspend(context: Any) {
        context.asDynamic().suspend().catch { _: dynamic -> }
    }

    override fun close(context: Any) {
        context.asDynamic().close().catch { _: dynamic -> }
    }

    override fun newNode(context: Any, name: String, channels: Int): Any {
        val node = js(
            "new AudioWorkletNode(context, name, { numberOfInputs: 0, numberOfOutputs: 1, " +
                "outputChannelCount: [channels], processorOptions: { channels: channels } })",
        )
        node.connect(context.asDynamic().destination)
        return node.unsafeCast<Any>()
    }

    override fun listen(node: Any, returned: (slot: Int, underrunFrames: Int, block: Any) -> Unit, failed: (String) -> Unit) {
        val target = node.asDynamic()
        target.port.onmessage = { event: dynamic ->
            val message = event.data
            returned(message.slot.unsafeCast<Int>(), message.underrun.unsafeCast<Int>(), message.data.unsafeCast<Any>())
        }
        target.onprocessorerror = { _: dynamic -> failed("the processor threw") }
    }

    override fun release(node: Any) {
        val target = node.asDynamic()
        target.port.onmessage = null
        target.onprocessorerror = null
        target.port.postMessage(js("({ stop: true })"))
        target.disconnect()
    }

    override fun newGain(context: Any): Any {
        val gain = context.asDynamic().createGain()
        gain.connect(context.asDynamic().destination)
        return gain.unsafeCast<Any>()
    }

    override fun disconnect(node: Any) {
        node.asDynamic().disconnect()
    }

    override fun playBuffer(context: Any, destination: Any, channels: Int, frames: Int, block: Any, at: Double) {
        val target = context.asDynamic()
        val buffer = target.createBuffer(channels, frames, target.sampleRate)
        val data = block.asDynamic()
        for (c in 0 until channels) {
            val plane = buffer.getChannelData(c)
            var i = c
            for (k in 0 until frames) {
                plane[k] = data[i]
                i += channels
            }
        }
        val source = target.createBufferSource()
        source.buffer = buffer
        source.connect(destination)
        source.start(at)
    }

    override fun startTimer(intervalMs: Int, tick: () -> Unit): Any =
        js("setInterval")(tick, intervalMs).unsafeCast<Any>()

    override fun stopTimer(timer: Any) {
        js("clearInterval")(timer)
    }

    override fun newBlock(samples: Int): Any = js("new Float32Array(samples)").unsafeCast<Any>()

    // A Kotlin/JS FloatArray is a Float32Array, so this is one native copy.
    override fun fill(block: Any, samples: FloatArray) {
        block.asDynamic().set(samples)
    }

    override fun post(node: Any, slot: Int, block: Any) {
        node.asDynamic().port.postMessage(js("({ slot: slot, data: block })"), arrayOf(block.asDynamic().buffer))
    }
}

/**
 * Opens an output for this platform.
 *
 * It plays one or two channels and ignores the speaker positions of the layout. In a browser
 * this is Web Audio: an audio worklet where the page can run one, and scheduled buffers where it
 * cannot. See [AudioRenderCallback] for where rendering runs.
 * [AudioOutputEvent.Started] arrives only once the browser lets the page play sound, which usually
 * takes a click or a key press first. A start made before then waits, and calling
 * [AudioOutput.start] again from the click or key handler asks the browser once more.
 *
 * @throws UnsupportedOperationException in a host with no Web Audio, such as Node.js.
 * @throws IllegalArgumentException for an invalid buffer size, a layout of more than two channels,
 *   or a sample rate the browser refuses.
 */
public actual fun openAudioOutput(
    preferredFormat: AudioFormat,
    bufferFrames: Int,
    onEvent: (AudioOutputEvent) -> Unit,
): AudioOutput = openBrowserOutput(JsWebAudio, preferredFormat, bufferFrames, BrowserTechnique.Auto, onEvent)

/** True where the host has Web Audio: a browser page, but not Node.js. */
public actual fun audioOutputAvailable(): Boolean = JsWebAudio.hasAudioContext()
