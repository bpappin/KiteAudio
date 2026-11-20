package android.media

import java.util.concurrent.atomic.AtomicInteger

class AudioAttributes {
    class Builder { fun setUsage(value: Int) = this; fun setContentType(value: Int) = this; fun build() = AudioAttributes() }
    companion object { const val USAGE_MEDIA = 1; const val CONTENT_TYPE_MUSIC = 2 }
}
class AudioFormat {
    class Builder { fun setEncoding(value: Int) = this; fun setSampleRate(value: Int) = this; fun setChannelMask(value: Int) = this; fun build() = AudioFormat() }
    companion object { const val CHANNEL_OUT_MONO = 4; const val CHANNEL_OUT_STEREO = 12; const val ENCODING_PCM_16BIT = 2 }
}
class AudioTrack {
    var state = STATE_INITIALIZED
    var released = false
    val bufferSizeInFrames = 1024
    val writes = AtomicInteger()
    fun play() { check(!released && !throwPlay) { "injected play failure" } }
    fun pause() { onPause() }
    fun flush() {}
    fun release() { released = true; state = 0 }
    fun write(data: ShortArray, offset: Int, count: Int): Int { writes.incrementAndGet(); return writer(data, offset, count) }
    class Builder {
        fun setAudioAttributes(value: AudioAttributes) = this
        fun setAudioFormat(value: AudioFormat) = this
        fun setTransferMode(value: Int) = this
        fun setBufferSizeInBytes(value: Int) = this
        fun setPerformanceMode(value: Int) = this
        fun build() = AudioTrack().also { last = it }
    }
    companion object {
        const val STATE_INITIALIZED = 1
        const val MODE_STREAM = 1
        const val PERFORMANCE_MODE_LOW_LATENCY = 1
        lateinit var last: AudioTrack
        var throwPlay = false
        var writer: (ShortArray, Int, Int) -> Int = { _, _, count -> Thread.sleep(1); count }
        var onPause: () -> Unit = {}
        fun getMinBufferSize(rate: Int, mask: Int, encoding: Int) = 4096
    }
}
