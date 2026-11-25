@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

@JsFun("() => typeof AudioContext !== 'undefined'")
private external fun webAudioHasContext(): Boolean

@JsFun("() => typeof AudioWorkletNode !== 'undefined'")
private external fun webAudioHasWorklet(): Boolean

@JsFun("(context) => context.currentTime")
private external fun webAudioCurrentTime(context: JsAny): Double

@JsFun("(context) => { const gain = context.createGain(); gain.connect(context.destination); return gain; }")
private external fun webAudioGain(context: JsAny): JsAny

@JsFun("(node) => { node.disconnect(); }")
private external fun webAudioDisconnect(node: JsAny)

@JsFun(
    "(context, destination, channels, frames, block, at) => { " +
        "const buffer = context.createBuffer(channels, frames, context.sampleRate); " +
        "for (let c = 0; c < channels; c++) { const plane = buffer.getChannelData(c); " +
        "for (let k = 0, i = c; k < frames; k++, i += channels) plane[k] = block[i]; } " +
        "const source = context.createBufferSource(); source.buffer = buffer; " +
        "source.connect(destination); source.start(at); }",
)
private external fun webAudioPlayBuffer(context: JsAny, destination: JsAny, channels: Int, frames: Int, block: JsAny, at: Double)

@JsFun("(interval, tick) => setInterval(() => tick(), interval)")
private external fun webAudioStartTimer(interval: Int, tick: () -> Unit): JsAny

@JsFun("(timer) => { clearInterval(timer); }")
private external fun webAudioStopTimer(timer: JsAny)

@JsFun("(rate) => new AudioContext({ sampleRate: rate, latencyHint: 'interactive' })")
private external fun webAudioContext(rate: Int): JsAny

@JsFun("(context) => typeof context.baseLatency === 'number' ? context.baseLatency : 0")
private external fun webAudioBaseLatency(context: JsAny): Double

@JsFun(
    "(context, source, done, failed) => { " +
        "try { " +
        "const url = URL.createObjectURL(new Blob([source], { type: 'application/javascript' })); " +
        "context.audioWorklet.addModule(url).then(" +
        "() => { URL.revokeObjectURL(url); done(); }, " +
        "(problem) => { URL.revokeObjectURL(url); failed(String(problem)); }); " +
        "} catch (problem) { failed(String(problem)); } }",
)
private external fun webAudioLoad(context: JsAny, source: String, done: () -> Unit, failed: (String) -> Unit)

@JsFun("(context, changed) => { context.onstatechange = () => changed(context.state); }")
private external fun webAudioOnStateChange(context: JsAny, changed: (String) -> Unit)

@JsFun("(context, done, failed) => { context.resume().then(() => done(), (problem) => failed(String(problem))); }")
private external fun webAudioResume(context: JsAny, done: () -> Unit, failed: (String) -> Unit)

@JsFun("(context) => { context.suspend().catch(() => {}); }")
private external fun webAudioSuspend(context: JsAny)

@JsFun("(context) => { context.close().catch(() => {}); }")
private external fun webAudioClose(context: JsAny)

@JsFun(
    "(context, name, channels) => { " +
        "const node = new AudioWorkletNode(context, name, { numberOfInputs: 0, numberOfOutputs: 1, " +
        "outputChannelCount: [channels], processorOptions: { channels: channels } }); " +
        "node.connect(context.destination); return node; }",
)
private external fun webAudioNode(context: JsAny, name: String, channels: Int): JsAny

@JsFun(
    "(node, returned, failed) => { " +
        "node.port.onmessage = (event) => { const m = event.data; returned(m.slot, m.underrun, m.data); }; " +
        "node.onprocessorerror = () => failed('the processor threw'); }",
)
private external fun webAudioListen(node: JsAny, returned: (Int, Int, JsAny) -> Unit, failed: (String) -> Unit)

@JsFun(
    "(node) => { node.port.onmessage = null; node.onprocessorerror = null; " +
        "node.port.postMessage({ stop: true }); node.disconnect(); }",
)
private external fun webAudioRelease(node: JsAny)

@JsFun("(samples) => new Float32Array(samples)")
private external fun webAudioBlock(samples: Int): JsAny

/**
 * Writes the floats [packSamples] packed into [block], a `Float32Array` of the same length, through
 * a byte view of its buffer.
 */
@JsFun(
    "(block, packed) => { " +
        "const bytes = new Uint8Array(block.buffer, block.byteOffset, packed.length); " +
        "for (let i = 0; i < packed.length; i++) bytes[i] = packed.charCodeAt(i); }",
)
private external fun webAudioFill(block: JsAny, packed: String)

@JsFun("(node, slot, block) => { node.port.postMessage({ slot: slot, data: block }, [block.buffer]); }")
private external fun webAudioPost(node: JsAny, slot: Int, block: JsAny)

@JsFun("() => typeof AudioContext !== 'undefined' && 'setSinkId' in AudioContext.prototype")
private external fun webAudioCanChooseSink(): Boolean

@JsFun("(rate, sinkId) => new AudioContext({ sampleRate: rate, latencyHint: 'interactive', sinkId: sinkId })")
private external fun webAudioContextOn(rate: Int, sinkId: String): JsAny

@JsFun("(context, failed) => { context.onerror = () => failed('the audio context reported an error'); }")
private external fun webAudioOnError(context: JsAny, failed: (String) -> Unit)

@JsFun(
    "(add, done, failed) => { " +
        "const media = (typeof navigator !== 'undefined') ? navigator.mediaDevices : undefined; " +
        "if (!media || !media.enumerateDevices) { done(); return; } " +
        "try { media.enumerateDevices().then((list) => { " +
        "for (const entry of list) if (entry.kind === 'audiooutput') add(entry.deviceId, entry.label); done(); }, " +
        "(problem) => failed(String(problem))); } catch (problem) { failed(String(problem)); } }",
)
private external fun webAudioListOutputs(add: (String, String) -> Unit, done: () -> Unit, failed: (String) -> Unit)

/** Web Audio through `@JsFun` imports. A handle is a [JsAny]. */
internal object WasmWebAudio : WebAudioApi<JsAny> {
    override fun hasAudioContext(): Boolean = webAudioHasContext()
    override fun hasWorklet(): Boolean = webAudioHasWorklet()
    override fun currentTime(context: JsAny): Double = webAudioCurrentTime(context)
    override fun newGain(context: JsAny): JsAny = webAudioGain(context)
    override fun disconnect(node: JsAny) = webAudioDisconnect(node)
    override fun playBuffer(context: JsAny, destination: JsAny, channels: Int, frames: Int, block: JsAny, at: Double) =
        webAudioPlayBuffer(context, destination, channels, frames, block, at)
    override fun startTimer(intervalMs: Int, tick: () -> Unit): JsAny = webAudioStartTimer(intervalMs, tick)
    override fun stopTimer(timer: JsAny) = webAudioStopTimer(timer)
    override fun newContext(sampleRate: Int): JsAny = webAudioContext(sampleRate)
    override fun baseLatencySeconds(context: JsAny): Double = webAudioBaseLatency(context)
    override fun loadProcessor(context: JsAny, source: String, done: () -> Unit, failed: (String) -> Unit) =
        webAudioLoad(context, source, done, failed)
    override fun onStateChange(context: JsAny, changed: (String) -> Unit) = webAudioOnStateChange(context, changed)
    override fun resume(context: JsAny, done: () -> Unit, failed: (String) -> Unit) = webAudioResume(context, done, failed)
    override fun suspend(context: JsAny) = webAudioSuspend(context)
    override fun close(context: JsAny) = webAudioClose(context)
    override fun newNode(context: JsAny, name: String, channels: Int): JsAny = webAudioNode(context, name, channels)
    override fun listen(node: JsAny, returned: (slot: Int, underrunFrames: Int, block: JsAny) -> Unit, failed: (String) -> Unit) =
        webAudioListen(node, returned, failed)
    override fun release(node: JsAny) = webAudioRelease(node)
    override fun newBlock(samples: Int): JsAny = webAudioBlock(samples)

    private var packing = CharArray(0)

    // Wasm memory is not a Float32Array and there is no bulk copy into one, so the block crosses
    // packed into one string instead of one call for each sample.
    override fun fill(block: JsAny, samples: FloatArray) {
        if (packing.size != samples.size * 4) packing = CharArray(samples.size * 4)
        webAudioFill(block, packSamples(samples, packing))
    }

    override fun post(node: JsAny, slot: Int, block: JsAny) = webAudioPost(node, slot, block)

    override fun canChooseSink(): Boolean = webAudioCanChooseSink()
    override fun newContextOn(sampleRate: Int, sinkId: String): JsAny = webAudioContextOn(sampleRate, sinkId)
    override fun onError(context: JsAny, failed: (String) -> Unit) = webAudioOnError(context, failed)
    override fun listOutputs(done: (List<Pair<String, String>>) -> Unit, failed: (String) -> Unit) {
        val outputs = mutableListOf<Pair<String, String>>()
        webAudioListOutputs({ id, label -> outputs += id to label }, { done(outputs) }, failed)
    }
}

private val wasmOutputDevices = BrowserOutputDevices(WasmWebAudio)

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
): AudioOutput = openBrowserOutput(WasmWebAudio, format, bufferFrames, BrowserTechnique.Auto, device, formatPolicy, wasmOutputDevices, onEvent)

/**
 * The outputs of `navigator.mediaDevices.enumerateDevices()`. The first one is the default. The
 * names are empty until the page has a media permission, for example from `getUserMedia`, and a
 * browser may list fewer outputs or none before then. Node.js and a page that is not a secure
 * context have no `navigator.mediaDevices`, so the list is empty there.
 */
public actual suspend fun audioOutputDevices(): List<AudioOutputDevice> = wasmOutputDevices.list()

/** True where the host has Web Audio: a browser page, but not Node.js. */
public actual fun audioOutputAvailable(): Boolean = WasmWebAudio.hasAudioContext()

/**
 * [samples] as one string of code units 0 to 255, four for each float, its bits in little-endian
 * order, built in [into], which holds four characters for each sample.
 *
 * Every code unit is below 0x100, so none falls in the surrogate range and no step on the way can
 * reinterpret one. Little-endian because a `Float32Array` reads its bytes in the host's order,
 * which is little-endian in every browser engine.
 */
internal fun packSamples(samples: FloatArray, into: CharArray): String {
    for (i in samples.indices) {
        val bits = samples[i].toRawBits()
        val at = i * 4
        into[at] = (bits and 0xFF).toChar()
        into[at + 1] = ((bits ushr 8) and 0xFF).toChar()
        into[at + 2] = ((bits ushr 16) and 0xFF).toChar()
        into[at + 3] = ((bits ushr 24) and 0xFF).toChar()
    }
    return into.concatToString()
}
