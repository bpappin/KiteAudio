package io.github.yuroyami.kitesynth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A two-pole resonant low-pass, the one every SoundFont synthesiser has, with FluidSynth's
 * coefficients. Its state belongs to one voice; its coefficients change at the control rate.
 */
public class LowPassFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    /** True at the top of the range with no resonance: the signal passes untouched, and costs nothing. */
    public var bypassed: Boolean = true
        private set

    /** Forgets the last samples, so a new note does not start with the old one's ring. */
    public fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    /** Cutoff in absolute cents (8.176 Hz times two to the cents over 1200), resonance in centibels. */
    public fun set(cutoffCents: Double, resonanceCb: Double, sampleRate: Int) {
        if (!cutoffCents.isFinite() || !resonanceCb.isFinite() || sampleRate <= 0) {
            // An invalid control value must not leave recursive coefficients poisoned after
            // the producer corrects it. Keep valid incoming audio available until then.
            reset()
            bypassed = true
            return
        }
        if (cutoffCents >= 13500.0 && resonanceCb <= 0.0) {
            bypassed = true
            return
        }
        bypassed = false
        val hertz = min(8.176 * 2.0.pow(cutoffCents.coerceAtLeast(1500.0) / 1200.0), 0.45 * sampleRate)
        // 3.01 dB less, so no resonance is a flat Butterworth response rather than a small peak.
        val q = 10.0.pow((resonanceCb.coerceIn(0.0, 960.0) / 10.0 - 3.01) / 20.0)
        // Pulls a resonant peak back down, and never lifts: an unresonant filter must match the bypass exactly,
        // or turning it on by a hair is a jump in loudness.
        val gain = if (q > 1.0) 1.0 / sqrt(q) else 1.0
        val omega = 2.0 * PI * hertz / sampleRate
        val alpha = sin(omega) / (2.0 * q)
        val a0 = 1.0 + alpha
        val c = cos(omega)
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
        b1 = (1.0 - c) / a0 * gain
        b0 = b1 / 2.0
        b2 = b0
    }

    public fun process(x: Float): Float {
        // x - x is 0 only for a finite x. Unlike isFinite(), it needs no native call in an Android debug build.
        if (x - x != 0f) {
            reset()
            return 0f
        }
        if (bypassed) return x
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        val output = y.toFloat()
        if (output - output != 0f) {
            // Check before committing history, including a finite Double that overflows
            // Float PCM. One failed sample must not poison every later note or effect send.
            reset()
            return 0f
        }
        x2 = x1
        x1 = x.toDouble()
        y2 = y1
        y1 = y
        return output
    }
}
