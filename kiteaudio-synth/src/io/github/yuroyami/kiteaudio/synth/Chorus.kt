package io.github.yuroyami.kiteaudio.synth

import kotlin.math.PI
import kotlin.math.sin

/**
 * A chorus: three copies of the input about 30 ms late, each wobbling 3.6 ms at 0.36 Hz a third of
 * a cycle apart, spread across the two sides. Those are GeneralUser GS's own suggested settings.
 *
 * Allocates once. [process] runs on the audio thread and does arithmetic only.
 */
public class Chorus(private val sampleRate: Int = 48_000) {

    /** How much of the chorus is mixed in, 0 to 1. */
    public var level: Double = 0.55
        set(value) { if (value.isFinite()) field = value.coerceIn(0.0, 1.0) }

    private val delayFrames = sampleRate * 0.030
    private val depthFrames = sampleRate * 0.0036
    private val increment = 0.36 / sampleRate
    private val line = FloatArray((delayFrames + depthFrames).toInt() + 4)
    private var write = 0
    private var phase = 0.0
    /** How long nothing has gone in, so a quiet chorus costs nothing once its line has emptied. */
    private var silentFrames = line.size

    /** Empties the line, so the last song does not leak into the next. */
    public fun clear() {
        line.fill(0f)
        write = 0
        phase = 0.0
        silentFrames = line.size
    }

    /**
     * Adds the chorus of [input] to [out]. Both are interleaved stereo; [input] is read from its
     * start and [out] written from frame [offset], for [frames] frames.
     */
    public fun process(input: FloatArray, out: FloatArray, offset: Int, frames: Int) {
        var quiet = true
        for (i in 0 until frames * 2) if (input[i] != 0f) {
            quiet = false
            break
        }
        if (quiet && silentFrames >= line.size) return
        silentFrames = if (quiet) silentFrames + frames else 0
        val gain = level.toFloat()
        for (i in 0 until frames) {
            val rawLeft = input[i * 2]
            val rawRight = input[i * 2 + 1]
            val left = if (rawLeft.isFinite()) rawLeft else 0f
            val right = if (rawRight.isFinite()) rawRight else 0f
            // Widen before summing so even two finite Float.MAX_VALUE inputs stay finite.
            line[write] = ((left.toDouble() + right) * 0.5).toFloat()
            val a = tap(phase)
            val b = tap(phase + 1.0 / 3.0)
            val c = tap(phase + 2.0 / 3.0)
            val at = (offset + i) * 2
            addFinite(out, at, gain * (a.toDouble() + 0.5 * b))
            addFinite(out, at + 1, gain * (c.toDouble() + 0.5 * b))
            write++
            if (write >= line.size) write = 0
            phase += increment
            if (phase >= 1.0) phase -= 1.0
        }
    }

    /** One copy, read between two frames of the line at its wobbling delay. */
    private fun tap(at: Double): Float {
        var read = write - (delayFrames + depthFrames * sin(2.0 * PI * at))
        if (read < 0) read += line.size
        val index = read.toInt()
        val fraction = (read - index).toFloat()
        val a = line[index % line.size]
        val b = line[(index + 1) % line.size]
        return (a.toDouble() + (b.toDouble() - a) * fraction).toFloat()
    }

    private fun addFinite(out: FloatArray, at: Int, wet: Double) {
        val dry = if (out[at].isFinite()) out[at] else 0f
        val mixed = (dry + wet).toFloat()
        out[at] = if (mixed.isFinite()) mixed else 0f
    }
}
