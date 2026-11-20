package io.github.yuroyami.kiteaudio.output

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

/** Blocking platform output with one owned, independently cancellable render generation. */
internal class JvmAudioSink(
    override val format: AudioFormat,
    private val line: SourceDataLine,
    private val bufferFrames: Int,
    private val onEvent: (AudioSinkEvent) -> Unit,
) : AudioSink {
    // Capacity is an estimate of buffering, not current queued depth or acoustic latency.
    override val latency: Latency
        get() = Latency(line.bufferSize / (format.channels * 2), LatencyQuality.Estimated)

    private class Generation {
        @Volatile var active: Boolean = false
        lateinit var worker: Thread
    }

    private val stateLock = Any()
    @Volatile private var generation: Generation? = null
    private var closed = false
    override val isRunning: Boolean get() = generation?.active == true

    override fun start(callback: AudioRenderCallback) {
        var failure: Throwable? = null
        var failedGeneration: Generation? = null
        synchronized(stateLock) {
            check(!closed) { "the audio sink is closed" }
            val previous = generation
            if (previous?.active == true) return
            check(previous == null) { "the previous audio callback is still stopping" }
            val next = Generation()
            next.worker = Thread({ pump(next, callback) }, "KiteRT-jvm-audio").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 2
            }
            generation = next
            try {
                line.start()
                next.active = true
                next.worker.start()
            } catch (problem: Throwable) {
                next.active = false
                haltOutput()
                failedGeneration = next
                failure = problem
            }
        }
        failure?.let {
            try { deliverAudioEvent(onEvent, AudioSinkEvent.Failed("the audio output would not start", it)) }
            finally { synchronized(stateLock) { if (generation === failedGeneration) generation = null } }
        }
    }

    private fun pump(current: Generation, callback: AudioRenderCallback) {
        try {
            val floats = FloatArray(bufferFrames * format.channels)
            val pcm = ByteArray(floats.size * 2)
            val observer: (AudioSinkEvent) -> Unit = { event ->
                if (event is AudioSinkEvent.Failed) current.active = false
                deliverAudioEvent(onEvent, event)
            }
            if (current.active) deliverAudioEvent(onEvent, AudioSinkEvent.Started(format, latency))
            while (current.active) {
                if (!renderAudioBlock(callback, floats, bufferFrames, format.framesToMicros(bufferFrames), observer)) break
                if (!current.active) break
                var offset = 0
                for (sample in floats) {
                    val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
                    pcm[offset++] = (value and 0xff).toByte()
                    pcm[offset++] = ((value shr 8) and 0xff).toByte()
                }
                var written = 0
                var noProgress = 0
                while (current.active && written < pcm.size) {
                    val count = line.write(pcm, written, pcm.size - written)
                    if (!current.active) break
                    if (count < 0 || count > pcm.size - written || count % (format.channels * 2) != 0) {
                        current.active = false
                        deliverAudioEvent(onEvent, AudioSinkEvent.DeviceLost("SourceDataLine.write returned invalid count $count"))
                        break
                    }
                    if (count == 0) {
                        if (++noProgress >= 10) {
                            current.active = false
                            deliverAudioEvent(onEvent, AudioSinkEvent.DeviceLost("SourceDataLine.write made no progress"))
                        } else {
                            Thread.sleep(1)
                        }
                    } else {
                        written += count
                        noProgress = 0
                    }
                }
            }
        } catch (problem: Throwable) {
            current.active = false
            deliverAudioEvent(onEvent, AudioSinkEvent.Failed("the audio worker failed", problem))
        } finally {
            current.active = false
            synchronized(stateLock) { haltOutput() }
            try {
                deliverAudioEvent(onEvent, AudioSinkEvent.Stopped)
            } finally {
                synchronized(stateLock) { if (generation === current) generation = null }
            }
        }
    }

    private fun haltOutput() {
        runCatching { line.stop() }
        runCatching { line.flush() }
    }

    override fun stop() {
        val current = synchronized(stateLock) {
            generation?.also {
                it.active = false
                // Unblock a platform write before waiting for the worker.
                haltOutput()
            }
        } ?: return
        if (Thread.currentThread() !== current.worker) {
            try { current.worker.join(500) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // The worker retains ownership until finally, even after the bounded wait expires.
    }

    override fun close() {
        synchronized(stateLock) { closed = true }
        stop()
        synchronized(stateLock) { runCatching { line.close() } }
    }
}

@Throws(Exception::class)
public actual fun openAudioSink(
    preferredFormat: AudioFormat,
    bufferFrames: Int,
    onEvent: (AudioSinkEvent) -> Unit,
): AudioSink {
    validateBufferFrames(preferredFormat, bufferFrames)
    val javaFormat = JavaAudioFormat(
        preferredFormat.sampleRate.toFloat(),
        16,
        preferredFormat.channels,
        true,          // signed
        false,         // little-endian, which is what every desktop wants
    )

    val info = DataLine.Info(SourceDataLine::class.java, javaFormat)
    val line = try {
        (AudioSystem.getLine(info) as SourceDataLine).also {
            // Ask for four blocks' worth. One block is a click waiting to happen; more than four
            // is latency nobody asked for.
            it.open(javaFormat, bufferFrames * preferredFormat.channels * 2 * 4)
        }
    } catch (problem: LineUnavailableException) {
        deliverAudioEvent(onEvent, AudioSinkEvent.Failed("no audio output is available", problem))
        throw problem
    }

    return JvmAudioSink(preferredFormat, line, bufferFrames, onEvent)
}

public actual fun audioOutputAvailable(): Boolean = runCatching {
    val format = JavaAudioFormat(48_000f, 16, 2, true, false)
    AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))
}.getOrDefault(false)
