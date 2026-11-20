package io.github.yuroyami.kitert

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process

/** Blocking platform output with one owned, independently cancellable render generation. */
private class AndroidAudioSink(
    override val format: AudioFormat,
    private val track: AudioTrack,
    private val bufferFrames: Int,
    private val onEvent: (AudioSinkEvent) -> Unit,
) : AudioSink {
    // Capacity is an estimate of buffering, not current queued depth or acoustic latency.
    override val latency: Latency
        get() = Latency(track.bufferSizeInFrames, LatencyQuality.Estimated)

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
            next.worker = Thread({ pump(next, callback) }, "KiteRT-android-audio").apply {
                isDaemon = true

            }
            generation = next
            try {
                track.play()
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val floats = FloatArray(bufferFrames * format.channels)
            val pcm = ShortArray(floats.size)
            val observer: (AudioSinkEvent) -> Unit = { event ->
                if (event is AudioSinkEvent.Failed) current.active = false
                deliverAudioEvent(onEvent, event)
            }
            if (current.active) deliverAudioEvent(onEvent, AudioSinkEvent.Started(format, latency))
            while (current.active) {
                if (!renderAudioBlock(callback, floats, bufferFrames, format.framesToMicros(bufferFrames), observer)) break
                if (!current.active) break
                for (i in floats.indices) {
                    pcm[i] = (floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                var written = 0
                var noProgress = 0
                while (current.active && written < pcm.size) {
                    val count = track.write(pcm, written, pcm.size - written)
                    if (!current.active) break
                    if (count < 0 || count > pcm.size - written || count % (format.channels) != 0) {
                        current.active = false
                        deliverAudioEvent(onEvent, AudioSinkEvent.DeviceLost("AudioTrack.write returned invalid count $count"))
                        break
                    }
                    if (count == 0) {
                        if (++noProgress >= 10) {
                            current.active = false
                            deliverAudioEvent(onEvent, AudioSinkEvent.DeviceLost("AudioTrack.write made no progress"))
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
        runCatching { track.pause() }
        runCatching { track.flush() }
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
        synchronized(stateLock) { runCatching { track.release() } }
    }
}

@Throws(Exception::class)
public actual fun openAudioSink(
    preferredFormat: AudioFormat,
    bufferFrames: Int,
    onEvent: (AudioSinkEvent) -> Unit,
): AudioSink {
    validateBufferFrames(preferredFormat, bufferFrames)
    val channelMask = if (preferredFormat.channels == 1) {
        AndroidAudioFormat.CHANNEL_OUT_MONO
    } else {
        AndroidAudioFormat.CHANNEL_OUT_STEREO
    }

    val minimum = AudioTrack.getMinBufferSize(
        preferredFormat.sampleRate,
        channelMask,
        AndroidAudioFormat.ENCODING_PCM_16BIT,
    )
    if (minimum <= 0) {
        deliverAudioEvent(onEvent, AudioSinkEvent.Failed("AudioTrack reports no usable buffer size ($minimum)"))
        throw UnsupportedOperationException("AudioTrack reports no usable buffer size")
    }

    // Whichever is larger: what the device insists on, or four of our blocks. Asking for less
    // than the minimum fails outright, and asking for one block is a click waiting to happen.
    val bytes = maxOf(minimum, bufferFrames * preferredFormat.channels * 2 * 4)

    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AndroidAudioFormat.Builder()
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(preferredFormat.sampleRate)
                .setChannelMask(channelMask)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bytes)
        .apply {
            // The low-latency flag exists from Oreo. On older devices the default path is what
            // there is.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
        }
        .build()

    if (track.state != AudioTrack.STATE_INITIALIZED) {
        track.release()
        deliverAudioEvent(onEvent, AudioSinkEvent.Failed("AudioTrack would not initialise"))
        throw UnsupportedOperationException("AudioTrack would not initialise")
    }

    return AndroidAudioSink(preferredFormat, track, bufferFrames, onEvent)
}

/**
 * True on every Android device.
 *
 * Android always has an audio output, even when it is only the earpiece. Whether this particular
 * code drives it correctly is a different question, and one nobody has answered yet.
 */
public actual fun audioOutputAvailable(): Boolean = true
