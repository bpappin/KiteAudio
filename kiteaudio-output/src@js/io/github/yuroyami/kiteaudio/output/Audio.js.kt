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

    override fun canChooseSink(): Boolean =
        js("typeof AudioContext !== 'undefined' && 'setSinkId' in AudioContext.prototype").unsafeCast<Boolean>()

    override fun newContextOn(sampleRate: Int, sinkId: String): Any =
        js("new AudioContext({ sampleRate: sampleRate, latencyHint: 'interactive', sinkId: sinkId })").unsafeCast<Any>()

    override fun onError(context: Any, failed: (String) -> Unit) {
        context.asDynamic().onerror = { _: dynamic -> failed("the audio context reported an error") }
    }

    override fun listOutputs(done: (List<Pair<String, String>>) -> Unit, failed: (String) -> Unit) {
        val media = js("(typeof navigator !== 'undefined' && navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) ? navigator.mediaDevices : null")
        if (media == null) {
            done(emptyList())
            return
        }
        try {
            media.enumerateDevices().then(
                { list: dynamic ->
                    val outputs = mutableListOf<Pair<String, String>>()
                    val count = list.length.unsafeCast<Int>()
                    for (i in 0 until count) {
                        val entry = list[i]
                        if (entry.kind == "audiooutput") outputs += entry.deviceId.unsafeCast<String>() to entry.label.unsafeCast<String>()
                    }
                    done(outputs)
                },
                { problem: dynamic -> failed(problem.toString().unsafeCast<String>()) },
            )
        } catch (problem: Throwable) {
            failed(problem.toString())
        }
    }
}

private val jsOutputDevices = BrowserOutputDevices(JsWebAudio)

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
 * A [device] must come from the last call of [audioOutputDevices]. The default entry plays in every
 * browser. Another entry needs an `AudioContext` that takes a `sinkId`, which is Chromium 110 and
 * newer today. Chromium 128 and newer report an output that fails while it plays with `DeviceLost`.
 *
 * @throws UnsupportedOperationException in a host with no Web Audio, such as Node.js, and for a
 *   [device] that is not the default in a browser that cannot choose an output.
 * @throws IllegalArgumentException for an invalid buffer size, a layout of more than two channels,
 *   a [device] that the last list did not hold, or a sample rate the browser refuses.
 */
public actual fun openAudioOutput(
    format: AudioFormat,
    bufferFrames: Int,
    device: AudioOutputDevice?,
    formatPolicy: AudioFormatPolicy,
    onEvent: (AudioOutputEvent) -> Unit,
): AudioOutput = openBrowserOutput(JsWebAudio, format, bufferFrames, BrowserTechnique.Auto, device, formatPolicy, jsOutputDevices, onEvent)

/**
 * The outputs of `navigator.mediaDevices.enumerateDevices()`. The first one is the default. The
 * names are empty until the page has a media permission, for example from `getUserMedia`, and a
 * browser may list fewer outputs or none before then. Node.js and a page that is not a secure
 * context have no `navigator.mediaDevices`, so the list is empty there.
 */
public actual suspend fun audioOutputDevices(): List<AudioOutputDevice> = jsOutputDevices.list()

/** True where the host has Web Audio: a browser page, but not Node.js. */
public actual fun audioOutputAvailable(): Boolean = JsWebAudio.hasAudioContext()
