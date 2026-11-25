package io.github.yuroyami.kiteaudio.synth

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Which part of its envelope a voice is in. */
public enum class EnvelopeStage { Delay, Attack, Hold, Decay, Sustain, Release, Finished }

/** How a voice reads between two frames of its sample. Cubic is cleaner; linear is cheaper. */
public enum class Interpolation { Linear, Cubic }

/**
 * How a voice reads the frames next to a loop boundary.
 *
 * The interpolation needs frames on both sides of the position. At a loop boundary, some of those
 * frames lie outside the loop. This library calls them guard frames. SoundFont 2.04 (section 6.3)
 * asks font authors to make the frames around the loop start match the frames around the loop end.
 * A font whose guard frames do not match can click at every loop, or sound different from what
 * its author heard.
 */
public enum class LoopBoundary {
    /**
     * Reads the guard frames that the font stores outside the loop, as SoundFont 2.04 describes.
     * This is the default. A font with matching guard frames sounds the same in both modes.
     */
    GuardFrames,

    /**
     * After the first wrap, reads the frames around the loop as a circle: the frame before the
     * loop start is the last frame of the loop, and the frame after the loop end is the first.
     * Guard frames that do not match the loop then cannot change the sound.
     *
     * The first pass through the loop reads as [GuardFrames], so the attack does not change.
     * The tail after a loop stops (a released [LoopMode.UntilRelease] note) does not change either.
     * The loop is the one the zone plays: the sample's loop points plus the zone's loop offsets.
     *
     * Choose it for a font whose loops click or sound different from what its author heard. It
     * costs one or two compares per output frame. It also ignores what the author put in the guard
     * frames, so a font that relies on them sounds different.
     */
    Wrapped,
}

/**
 * One sounding note.
 *
 * Reads straight out of its sample's own frames, so starting a voice copies nothing. Everything
 * it needs is worked out in [start]; [render] after that does arithmetic and nothing else, which
 * is what lets the render loop run without allocating.
 *
 * The envelopes and LFOs move every [CONTROL_FRAMES] frames and the level is ramped in a straight
 * line between, which keeps a fast attack smooth and a hundred voices cheap.
 *
 * Voices are pooled and reused. That is why this is a mutable object with a [start] rather than
 * a fresh one per note: a hundred notes a second on the audio thread must not make garbage.
 */
public class Voice {

    /** Nothing is playing until start() is called. */
    public var active: Boolean = false
        private set

    public var key: Int = -1
        private set

    public var channel: Int = 0
        private set

    /** Notes in the same class cut each other off. A hi-hat closing over an open one. */
    public var exclusiveClass: Int = 0
        private set

    /** Set when a voice is stopped by the key coming up, so stealing can prefer it. */
    public var releasing: Boolean = false
        private set

    public var stage: EnvelopeStage = EnvelopeStage.Finished
        private set

    /** How loud the envelope is now, 0 to 1, for stealing the quietest first. */
    public var currentGain: Double = 0.0
        private set

    /** Amplitude estimate computed only when stealing needs it, outside the render loop. */
    internal val audibility: Double
        get() {
            val samplePeak = plan?.samplePeak ?: return 0.0
            return (currentGain * baseGain * abs(tremolo) * samplePeak *
                maxOf(gainLeft, gainRight)).coerceIn(0.0, 1.0)
        }

    /** Counts up from zero, so the oldest voice is the one with the largest value. */
    public var age: Long = 0L
        private set

    private var plan: VoicePlan? = null
    private var velocity = 0
    private var effectiveKey = 0
    /** What the modulators add to each generator, by operator number. */
    private val modSum = DoubleArray(GENERATOR_COUNT)

    /**
     * The plan whose pitch values set this voice's pitch. It is [plan], except for the left side
     * of a stereo pair, which takes the right side's pitch (SoundFont 2.04, section 7.10).
     */
    private var pitchPlan: VoicePlan? = null
    private var paired = false
    private var pitchKey = 0
    private var pitchVelocity = 0
    /** The right side's modulator sums for a paired voice. Unpaired, [pitchSum] is [modSum]. */
    private val pairedSum = DoubleArray(GENERATOR_COUNT)
    private var pitchSum = modSum

    private var pool: ShortArray = ShortArray(0)
    private var startFrame = 0
    private var endFrame = 0
    private var loopStart = 0
    private var loopEnd = 0
    private var loopMode = LoopMode.NoLoop
    /** True once the position has wrapped from the loop end back to the loop start. */
    private var wrappedOnce = false

    private var position = 0.0
    private var step = 1.0
    private var unbentStep = 1.0
    /** The tuning the synth last gave: table, channel and bend. The LFOs and the modulation envelope move around it. */
    private var tuningCents = 0.0
    private var sampleRate = 48_000

    /** The tuning this note started with, from the table and A4. A later change of either leaves it. */
    internal var fixedCents: Double = 0.0
        private set

    /** Attenuation, velocity and the channel's volume as one gain, and the two sides of the pan. */
    private var baseGain = 0.0
    private var gainLeft = 0.5
    private var gainRight = 0.5
    /** How much of this voice goes to the room and to the chorus, 0 to 1. */
    private var reverbSend = 0.0
    private var chorusSend = 0.0

    // The volume envelope, in frames and in centibels of attenuation: 0 is full, 960 is silence.
    private var delayFrames = 0
    private var attackFrames = 0
    private var holdFrames = 0
    private var decayFrames = 0
    private var releaseFrames = 0
    private var sustainCb = 0.0
    private var stageFrames = 0
    private var envelopeCb = SILENT
    /** The attack rises in amplitude, not in decibels, which is what the format says. */
    private var attackLevel = 0.0

    private val modEnvelope = ModEnvelope()
    private val modLfo = Lfo()
    private val vibLfo = Lfo()

    // A paired voice runs the right side's modulation envelope and LFO a second time, for its pitch
    // only. Its own envelope and LFO still move its filter and volume. Unpaired, these are its own.
    private val pairedEnvelope = ModEnvelope()
    private val pairedLfo = Lfo()
    private var pitchEnvelope = modEnvelope
    private var pitchLfo = modLfo

    // How far each source moves its destination at full scale: cents of pitch, cents of cutoff, centibels.
    private var modEnvToPitch = 0.0
    private var modLfoToPitch = 0.0
    private var vibLfoToPitch = 0.0
    private var modEnvToCutoff = 0.0
    private var modLfoToCutoff = 0.0
    private var modLfoToVolume = 0.0
    /** Fine and coarse tune that modulators add, in cents. */
    private var pitchOffset = 0.0
    private var cutoff = 13500.0
    private var resonance = 0.0

    // What the last control step settled on, so an unmoving voice recomputes nothing.
    private var pitchCents = 0.0
    private var cutoffCents = Double.NaN
    private var tremolo = 1.0

    private val filter = LowPassFilter()

    // The level as rendered, ramped toward the value the next control step computed.
    private var countdown = 0
    private var level = 0.0
    private var levelStep = 0.0

    /**
     * Begins a note, as if on a channel nobody has touched: full volume and expression, pan in the middle.
     *
     * @param sampleRate the rate the output runs at, which is usually not the sample's own.
     */
    public fun start(
        plan: VoicePlan,
        sampleRate: Int,
        channel: Int,
        age: Long,
        tuningCents: Double = 0.0,
    ): Unit = startPrepared(
        plan = plan,
        key = plan.key,
        velocity = plan.velocity,
        sampleRate = sampleRate,
        channel = channel,
        age = age,
        tuningCents = tuningCents,
    )

    internal fun retune(tuningCents: Double) {
        this.tuningCents = tuningCents
        step = unbentStep * Units.centsToRatio(tuningCents + pitchCents)
    }

    /** Starts a pooled voice from an immutable region without creating a per-note plan. */
    internal fun startPrepared(
        plan: VoicePlan,
        key: Int,
        velocity: Int,
        sampleRate: Int,
        channel: Int,
        age: Long,
        tuningCents: Double = 0.0,
        /** The part of [tuningCents] that stays with this note however the channel is tuned later. */
        fixedCents: Double = 0.0,
        controllers: IntArray = UNTOUCHED_CONTROLLERS,
        pitchWheel: Int = 0,
        channelPressure: Int = 0,
        polyPressure: Int = 0,
        bendRange: Double = 2.0,
        /** The plan that sets the pitch: the right side's for the left side of a stereo pair. */
        pitchPlan: VoicePlan = plan,
    ) {
        this.plan = plan
        val fixedVelocity = plan[Generator.Velocity]
        val fixedKey = plan[Generator.Keynum]
        this.velocity = if (fixedVelocity in 0..127) fixedVelocity else velocity
        this.effectiveKey = if (fixedKey in 0..127) fixedKey else key
        this.pitchPlan = pitchPlan
        paired = pitchPlan !== plan
        if (paired) {
            val pitchFixedVelocity = pitchPlan[Generator.Velocity]
            val pitchFixedKey = pitchPlan[Generator.Keynum]
            pitchVelocity = if (pitchFixedVelocity in 0..127) pitchFixedVelocity else velocity
            pitchKey = if (pitchFixedKey in 0..127) pitchFixedKey else key
            pitchSum = pairedSum
            pitchEnvelope = pairedEnvelope
            pitchLfo = pairedLfo
        } else {
            pitchVelocity = this.velocity
            pitchKey = effectiveKey
            pitchSum = modSum
            pitchEnvelope = modEnvelope
            pitchLfo = modLfo
        }
        this.fixedCents = fixedCents
        this.pool = plan.sample.data
        this.channel = channel
        this.key = key
        this.age = age
        this.exclusiveClass = plan.exclusiveClass
        this.sampleRate = sampleRate

        startFrame = plan.startFrame.coerceIn(0, pool.size)
        endFrame = plan.endFrame.coerceIn(startFrame, pool.size)
        loopStart = plan.loopStartFrame.coerceIn(startFrame, endFrame)
        loopEnd = plan.loopEndFrame.coerceIn(loopStart, endFrame)
        // A font with a broken loop is common. Playing it once is right; looping over one frame
        // is a buzz at the sample rate, which is the worst sound a synthesiser can make.
        loopMode = if (loopEnd - loopStart < 2) LoopMode.NoLoop else plan.loopMode

        position = startFrame.toDouble()
        wrappedOnce = false

        // Modulators first: the envelope times take theirs here, once, and never again.
        evaluateAll(controllers, pitchWheel, channelPressure, polyPressure, bendRange)

        // The pitch plan's sample rate too, so both sides of a pair move by the same step.
        val cents = Units.keyCents(pitchKey, pitchPlan.rootKey, pitchPlan.scaleTuning, pitchPlan.tuneCents)
        unbentStep = Units.centsToRatio(cents) * pitchPlan.sample.sampleRate / sampleRate
        pitchCents = 0.0
        retune(tuningCents)

        // Hold and decay can shorten for high keys and lengthen for low ones, key 60 being the pivot.
        val spec = plan.volumeEnvelope
        val fromMiddle = 60 - effectiveKey
        delayFrames = framesOf(spec.delayTimecents + moved(Generator.DelayVolEnv), sampleRate)
        attackFrames = framesOf(spec.attackTimecents + moved(Generator.AttackVolEnv), sampleRate)
        holdFrames = framesOf(spec.holdTimecents + moved(Generator.HoldVolEnv) + plan[Generator.KeynumToVolEnvHold] * fromMiddle, sampleRate)
        decayFrames = framesOf(spec.decayTimecents + moved(Generator.DecayVolEnv) + plan[Generator.KeynumToVolEnvDecay] * fromMiddle, sampleRate)
        releaseFrames = framesOf(spec.releaseTimecents + moved(Generator.ReleaseVolEnv), sampleRate)
        sustainCb = (spec.sustainCb + modSum[Generator.SustainVolEnv.id]).coerceIn(0.0, 1440.0)

        startModulation(plan, modSum, effectiveKey, modEnvelope, modLfo, sampleRate)
        if (paired) startModulation(pitchPlan, pairedSum, pitchKey, pairedEnvelope, pairedLfo, sampleRate)
        // The vibrato LFO moves only the pitch, so it is the pitch plan's alone.
        vibLfo.start(
            framesOf(pitchPlan[Generator.DelayVibLfo] + moved(pitchSum, Generator.DelayVibLfo), sampleRate),
            Lfo.hertzOf(pitchPlan[Generator.FreqVibLfo] + moved(pitchSum, Generator.FreqVibLfo)), sampleRate,
        )

        filter.reset()
        derive()
        applyModulation()

        attackLevel = 0.0
        envelopeCb = SILENT
        releasing = false
        active = true
        enter(EnvelopeStage.Delay)
        countdown = 0
        level = envelopeLevel() * baseGain * tremolo
        levelStep = 0.0
        // What this voice is worth to the stealer, until it has rendered a block of its own.
        // Leaving it at whatever the last occupant died on reports a full note as silence, and
        // the very next note in the same block steals back the one just played.
        currentGain = 1.0
    }

    /** A controller, the wheel or a pressure moved: the modulators run again, and the note carries on. */
    internal fun modulate(controllers: IntArray, pitchWheel: Int, channelPressure: Int, polyPressure: Int, bendRange: Double) {
        if (!active) return
        evaluateAll(controllers, pitchWheel, channelPressure, polyPressure, bendRange)
        derive()
    }

    // A paired voice also hears what moves the right side's pitch modulators.
    internal fun watches(controller: Int): Boolean =
        plan?.watchedControllers?.get(controller) == true || (paired && pitchPlan?.watchedControllers?.get(controller) == true)

    internal val watchesPressure: Boolean
        get() = plan?.watchesPressure == true || (paired && pitchPlan?.watchesPressure == true)

    internal val watchesPolyPressure: Boolean
        get() = plan?.watchesPolyPressure == true || (paired && pitchPlan?.watchesPolyPressure == true)

    internal val watchesWheel: Boolean
        get() = plan?.watchesWheel == true || (paired && pitchPlan?.watchesWheel == true)

    /** The key came up. The note fades from wherever its envelope is, at the full-scale rate. */
    public fun release() {
        if (!active || stage == EnvelopeStage.Release || stage == EnvelopeStage.Finished) return
        envelopeCb = when (stage) {
            EnvelopeStage.Delay -> SILENT
            EnvelopeStage.Attack -> if (attackLevel <= 0.0) SILENT else (-200.0 * log10(attackLevel)).coerceAtMost(SILENT)
            else -> envelopeCb
        }
        releasing = true
        stage = if (releaseFrames <= 0 || envelopeCb >= SILENT) EnvelopeStage.Finished else EnvelopeStage.Release
        stageFrames = 0
        modEnvelope.release()
        if (paired) pairedEnvelope.release()
    }

    /** Stops at once. Used when a voice is stolen and there is no time to fade. */
    public fun kill() {
        active = false
        stage = EnvelopeStage.Finished
        level = 0.0
        levelStep = 0.0
        currentGain = 0.0
        plan = null
        pitchPlan = null
        paired = false
        pool = EMPTY_POOL
    }

    /**
     * Adds this voice into an interleaved stereo buffer.
     *
     * Adds rather than writes, because every voice shares one buffer.
     */
    public fun render(out: FloatArray, offset: Int, frames: Int, channelGain: Double = 1.0): Unit =
        renderInto(out, null, null, offset, 0, frames, channelGain, cubic = true)

    /** The same, and the sends into the two buses, written from [busOffset]. */
    internal fun renderInto(
        out: FloatArray,
        reverbBus: FloatArray?,
        chorusBus: FloatArray?,
        offset: Int,
        busOffset: Int,
        frames: Int,
        channelGain: Double = 1.0,
        cubic: Boolean = true,
        /** True for [LoopBoundary.Wrapped]. */
        wrapped: Boolean = false,
    ) {
        if (!active) return
        val left = gainLeft * channelGain
        val right = gainRight * channelGain
        val toRoom = if (reverbBus != null) reverbSend.toFloat() else 0f
        val toChorus = if (chorusBus != null) chorusSend.toFloat() else 0f
        var done = 0
        while (done < frames) {
            if (countdown == 0) {
                // The block before ramped a finished voice down to nothing; now it can go.
                if (stage == EnvelopeStage.Finished) {
                    kill()
                    return
                }
                advanceEnvelope(CONTROL_FRAMES)
                modEnvelope.advance(CONTROL_FRAMES)
                modLfo.advance(CONTROL_FRAMES)
                vibLfo.advance(CONTROL_FRAMES)
                if (paired) {
                    pairedEnvelope.advance(CONTROL_FRAMES)
                    pairedLfo.advance(CONTROL_FRAMES)
                }
                applyModulation()
                val envelope = envelopeLevel()
                levelStep = (envelope * baseGain * tremolo - level) / CONTROL_FRAMES
                currentGain = envelope
                countdown = CONTROL_FRAMES
            }
            val run = min(countdown, frames - done)
            var at = offset + done * 2
            var bus = busOffset + done * 2
            var i = 0
            while (i < run) {
                val sample = if (stage == EnvelopeStage.Delay) 0f else filter.process(readSample(cubic, wrapped))
                if (!active) return
                level += levelStep
                val value = sample * level
                val l = (value * left).toFloat()
                val r = (value * right).toFloat()
                out[at] += l
                out[at + 1] += r
                if (toRoom > 0f) {
                    reverbBus!![bus] += l * toRoom
                    reverbBus[bus + 1] += r * toRoom
                }
                if (toChorus > 0f) {
                    chorusBus!![bus] += l * toChorus
                    chorusBus[bus + 1] += r * toChorus
                }
                at += 2
                bus += 2
                i++
            }
            countdown -= run
            done += run
        }
    }

    /**
     * The sample at the current position: four-point Hermite between frames, or a straight line when [cubic] is off.
     * With [wrapped], the taps next to the loop boundary read the loop as a circle once the voice has wrapped.
     */
    private fun readSample(cubic: Boolean, wrapped: Boolean): Float {
        // A sample that is not looping right now has an end, whatever its loop mode says. A
        // released UntilRelease loop stops looping, so without this it walks past its own last
        // frame and never ends.
        val looping = loopMode == LoopMode.Continuous ||
            (loopMode == LoopMode.UntilRelease && !releasing)
        if (position >= endFrame && !looping) {
            kill()
            return 0f
        }

        val index = position.toInt()
        val fraction = (position - index).toFloat()
        // Before the first wrap the frame before the loop start is the attack, so it stays raw.
        // After it, the voice came to the loop start from the loop end.
        val circular = wrapped && wrappedOnce

        val value = if (cubic) {
            val before = if (circular && index - 1 < loopStart) {
                frameAt(index - 1 + (loopEnd - loopStart), looping)
            } else {
                frameAt(index - 1, looping)
            }
            val here = frameAt(index, looping)
            val next = frameAt(index + 1, looping)
            val after = frameAt(index + 2, looping)
            val c1 = 0.5f * (next - before)
            val c2 = before - 2.5f * here + 2f * next - 0.5f * after
            val c3 = 0.5f * (after - before) + 1.5f * (here - next)
            (((c3 * fraction + c2) * fraction + c1) * fraction + here) / 32768.0f
        } else {
            val a = pool.getOrElse(index) { 0 }.toFloat()
            // After the first wrap, the frame after the loop end is the loop start. A released loop reads on into the tail.
            val nextIndex = if (circular && looping && index + 1 >= loopEnd) index + 1 - (loopEnd - loopStart) else index + 1
            val b = pool.getOrElse(nextIndex) { 0 }.toFloat()
            (a + (b - a) * fraction) / 32768.0f
        }

        position += step

        if (looping && position >= loopEnd) {
            // Wrap by the loop's length rather than jumping to its start, so a step larger than
            // one frame does not lose its place inside the loop.
            val length = (loopEnd - loopStart).toDouble()
            position = loopStart + (position - loopEnd) % length
            wrappedOnce = true
        }
        return value
    }

    /** A frame for the four taps: past a loop's end they wrap to its start, outside the sample they are its edge. */
    private fun frameAt(index: Int, looping: Boolean): Float {
        var at = index
        if (looping && at >= loopEnd) at -= loopEnd - loopStart
        if (at < 0) at = 0
        if (at >= pool.size) at = pool.size - 1
        return if (pool.isEmpty()) 0f else pool[at].toFloat()
    }

    // ------------------------------------------------------------------ modulation

    private fun moved(generator: Generator): Int = moved(modSum, generator)

    private fun moved(sum: DoubleArray, generator: Generator): Int = sum[generator.id].roundToInt()

    /** This voice's own modulator sums, and for a paired voice the right side's too. */
    private fun evaluateAll(controllers: IntArray, pitchWheel: Int, channelPressure: Int, polyPressure: Int, bendRange: Double) {
        evaluate(plan, modSum, effectiveKey, velocity, controllers, pitchWheel, channelPressure, polyPressure, bendRange)
        if (paired) {
            evaluate(pitchPlan, pairedSum, pitchKey, pitchVelocity, controllers, pitchWheel, channelPressure, polyPressure, bendRange)
        }
    }

    /** Every modulator's contribution in [plan], summed by destination into [sum]. */
    private fun evaluate(
        plan: VoicePlan?,
        sum: DoubleArray,
        key: Int,
        velocity: Int,
        controllers: IntArray,
        pitchWheel: Int,
        channelPressure: Int,
        polyPressure: Int,
        bendRange: Double,
    ) {
        sum.fill(0.0)
        val modulators = plan?.modulatorArray ?: return
        var index = 0
        while (index < modulators.size) {
            val modulator = modulators[index++]
            val destination = modulator.destinationId
            // A link feeds another modulator, which nothing here does yet; it reads as nothing.
            if (destination < 0 || destination >= GENERATOR_COUNT) continue
            val primary = sourceValue(modulator.source, key, velocity, controllers, pitchWheel, channelPressure, polyPressure, bendRange)
            if (primary == 0.0) continue
            val secondary = sourceValue(modulator.amountSource, key, velocity, controllers, pitchWheel, channelPressure, polyPressure, bendRange)
            var value = modulator.amount * primary * secondary
            if (modulator.transform == ABSOLUTE) value = abs(value)
            sum[destination] += value
        }
    }

    private fun sourceValue(
        source: Int,
        key: Int,
        velocity: Int,
        controllers: IntArray,
        pitchWheel: Int,
        channelPressure: Int,
        polyPressure: Int,
        bendRange: Double,
    ): Double {
        if ((source and CC_FLAG) != 0) return sourceCurve(source, controllers[source and 0x7F])
        val raw = when (source and 0x7F) {
            // No controller counts as a full one, so an amount source of nothing leaves the amount as it is.
            SOURCE_NONE -> return 1.0
            SOURCE_VELOCITY -> velocity
            SOURCE_KEY -> key
            SOURCE_POLY_PRESSURE -> polyPressure
            SOURCE_PRESSURE -> channelPressure
            SOURCE_WHEEL -> pitchWheel + 8192
            SOURCE_WHEEL_RANGE -> bendRange.roundToInt()
            else -> return 0.0
        }
        return sourceCurve(source, raw)
    }

    /** What the generators and the modulators come to: gain, pan, cutoff and every depth. */
    private fun derive() {
        val plan = plan ?: return
        val attenuation = (plan.attenuationCb + modSum[Generator.InitialAttenuation.id]).coerceIn(0.0, 1440.0)
        baseGain = Units.gainOf(attenuation)
        val pan = ((plan.pan + modSum[Generator.Pan.id]) / 500.0).coerceIn(-1.0, 1.0)
        gainLeft = (1.0 - pan) * 0.5
        gainRight = (1.0 + pan) * 0.5
        // The pitch depths and the modulated tune come from the pitch plan, the right side's for a pair.
        val pitchPlan = pitchPlan ?: plan
        val pitchSum = pitchSum
        modEnvToPitch = pitchPlan[Generator.ModEnvToPitch] + pitchSum[Generator.ModEnvToPitch.id]
        modLfoToPitch = pitchPlan[Generator.ModLfoToPitch] + pitchSum[Generator.ModLfoToPitch.id]
        vibLfoToPitch = pitchPlan[Generator.VibLfoToPitch] + pitchSum[Generator.VibLfoToPitch.id]
        pitchOffset = pitchSum[Generator.FineTune.id] + 100.0 * pitchSum[Generator.CoarseTune.id]
        modEnvToCutoff = plan[Generator.ModEnvToFilterFc] + modSum[Generator.ModEnvToFilterFc.id]
        modLfoToCutoff = plan[Generator.ModLfoToFilterFc] + modSum[Generator.ModLfoToFilterFc.id]
        modLfoToVolume = plan[Generator.ModLfoToVolume] + modSum[Generator.ModLfoToVolume.id]
        // SoundFont sends are in tenths of a percent: 200 is 20%, 1000 is full level.
        reverbSend = ((plan[Generator.ReverbEffectsSend] + modSum[Generator.ReverbEffectsSend.id]) / 1000.0).coerceIn(0.0, 1.0)
        chorusSend = ((plan[Generator.ChorusEffectsSend] + modSum[Generator.ChorusEffectsSend.id]) / 1000.0).coerceIn(0.0, 1.0)
        cutoff = plan[Generator.InitialFilterFc] + modSum[Generator.InitialFilterFc.id]
        resonance = plan[Generator.InitialFilterQ] + modSum[Generator.InitialFilterQ.id]
        // The next control step sets the filter again, from these.
        cutoffCents = Double.NaN
    }

    /** Pitch, cutoff and tremolo from where the modulation envelope and the LFOs are now. */
    private fun applyModulation() {
        val pitch = pitchOffset + pitchEnvelope.value * modEnvToPitch + pitchLfo.value * modLfoToPitch + vibLfo.value * vibLfoToPitch
        if (pitch != pitchCents) {
            pitchCents = pitch
            step = unbentStep * Units.centsToRatio(tuningCents + pitch)
        }
        val modValue = modEnvelope.value
        val fc = cutoff + modValue * modEnvToCutoff + modLfo.value * modLfoToCutoff
        if (fc != cutoffCents) {
            cutoffCents = fc
            filter.set(fc, resonance, sampleRate)
        }
        // A positive amount makes the LFO's rise louder, as the format says.
        tremolo = if (modLfoToVolume == 0.0) 1.0 else 10.0.pow(modLfo.value * modLfoToVolume / 200.0)
    }

    // ------------------------------------------------------------------ envelopes

    /** Moves the volume envelope on by [frames], through as many stages as that takes. */
    private fun advanceEnvelope(frames: Int) {
        var left = frames
        while (left > 0) {
            when (stage) {
                EnvelopeStage.Delay -> {
                    val take = min(left, delayFrames - stageFrames)
                    stageFrames += take
                    left -= take
                    if (stageFrames >= delayFrames) enter(EnvelopeStage.Attack)
                }
                EnvelopeStage.Attack -> {
                    val take = min(left, attackFrames - stageFrames)
                    stageFrames += take
                    left -= take
                    attackLevel = stageFrames.toDouble() / attackFrames
                    if (stageFrames >= attackFrames) enter(EnvelopeStage.Hold)
                }
                EnvelopeStage.Hold -> {
                    val take = min(left, holdFrames - stageFrames)
                    stageFrames += take
                    left -= take
                    if (stageFrames >= holdFrames) enter(EnvelopeStage.Decay)
                }
                EnvelopeStage.Decay -> {
                    // The time is for a fall from full to silence; the sustain level stops it early.
                    val target = min(sustainCb, SILENT)
                    val rate = SILENT / decayFrames
                    val take = min(left, ceil((target - envelopeCb) / rate).toInt().coerceAtLeast(0))
                    envelopeCb += rate * take
                    left -= take
                    if (envelopeCb >= target) reachSustain()
                }
                EnvelopeStage.Release -> {
                    envelopeCb += SILENT / releaseFrames * left
                    left = 0
                    if (envelopeCb >= SILENT) {
                        envelopeCb = SILENT
                        stage = EnvelopeStage.Finished
                    }
                }
                EnvelopeStage.Sustain, EnvelopeStage.Finished -> left = 0
            }
        }
    }

    /** Enters a stage, falling straight through any of no length: a font that says no delay means none at all. */
    private fun enter(next: EnvelopeStage) {
        stage = next
        stageFrames = 0
        when (next) {
            EnvelopeStage.Delay -> if (delayFrames <= 0) enter(EnvelopeStage.Attack)
            EnvelopeStage.Attack -> if (attackFrames <= 0) enter(EnvelopeStage.Hold)
            EnvelopeStage.Hold -> {
                attackLevel = 1.0
                envelopeCb = 0.0
                if (holdFrames <= 0) enter(EnvelopeStage.Decay)
            }
            EnvelopeStage.Decay -> if (decayFrames <= 0) reachSustain()
            else -> Unit
        }
    }

    // A sustain level of silence is the end of the note, not a slot to hold until the key comes
    // up. Percussive fonts decay to nothing, and holding one dead voice per note struck runs a
    // fast passage out of voices while sounding nothing.
    private fun reachSustain() {
        if (sustainCb >= SILENT) {
            envelopeCb = SILENT
            stage = EnvelopeStage.Finished
        } else {
            envelopeCb = sustainCb
            stage = EnvelopeStage.Sustain
        }
    }

    private fun envelopeLevel(): Double = when (stage) {
        EnvelopeStage.Delay, EnvelopeStage.Finished -> 0.0
        EnvelopeStage.Attack -> attackLevel
        else -> Units.gainOf(envelopeCb)
    }

    /** Starts a modulation envelope and the modulation LFO from [plan], its modulator sums and its key. */
    private fun startModulation(plan: VoicePlan, sum: DoubleArray, key: Int, envelope: ModEnvelope, lfo: Lfo, sampleRate: Int) {
        // Hold and decay can shorten for high keys and lengthen for low ones, key 60 being the pivot.
        val fromMiddle = 60 - key
        envelope.start(
            delayFrames = framesOf(plan[Generator.DelayModEnv] + moved(sum, Generator.DelayModEnv), sampleRate),
            attackFrames = framesOf(plan[Generator.AttackModEnv] + moved(sum, Generator.AttackModEnv), sampleRate),
            holdFrames = framesOf(
                plan[Generator.HoldModEnv] + moved(sum, Generator.HoldModEnv) + plan[Generator.KeynumToModEnvHold] * fromMiddle, sampleRate,
            ),
            decayFrames = framesOf(
                plan[Generator.DecayModEnv] + moved(sum, Generator.DecayModEnv) + plan[Generator.KeynumToModEnvDecay] * fromMiddle, sampleRate,
            ),
            releaseFrames = framesOf(plan[Generator.ReleaseModEnv] + moved(sum, Generator.ReleaseModEnv), sampleRate),
            sustain = (1.0 - (plan[Generator.SustainModEnv] + sum[Generator.SustainModEnv.id]) / 1000.0).coerceIn(0.0, 1.0),
        )
        lfo.start(
            framesOf(plan[Generator.DelayModLfo] + moved(sum, Generator.DelayModLfo), sampleRate),
            Lfo.hertzOf(plan[Generator.FreqModLfo] + moved(sum, Generator.FreqModLfo)), sampleRate,
        )
    }

    private fun framesOf(timecents: Int, sampleRate: Int): Int {
        // -12000 is the shortest time the format can say, and reads as instant. The longest is
        // its own limit, about a hundred seconds.
        if (timecents <= -12000) return 0
        return (Units.timecentsToSeconds(timecents.coerceAtMost(MAX_TIMECENTS)) * sampleRate).roundToInt()
    }

    private companion object {
        /** How often the envelopes and LFOs move, in frames. */
        const val CONTROL_FRAMES = 16
        const val SILENT = 960.0

        const val MAX_TIMECENTS = 8000
        const val ABSOLUTE = 2
        val EMPTY_POOL = ShortArray(0)

        /** A channel nobody has touched: full volume, expression and room, pan in the middle, no chorus. */
        val UNTOUCHED_CONTROLLERS = IntArray(128).also {
            it[7] = 127
            it[10] = 64
            it[11] = 127
            it[91] = 127
        }
    }
}

/**
 * The modulation envelope: it runs from 0 to 1 through the same stages as the volume envelope, and
 * turns with the same key and release. It moves in straight lines at the full-scale rate.
 */
internal class ModEnvelope {
    var value = 0.0
        private set
    private var stage = EnvelopeStage.Finished
    private var delayFrames = 0
    private var attackFrames = 0
    private var holdFrames = 0
    private var decayFrames = 0
    private var releaseFrames = 0
    private var sustain = 0.0
    private var stageFrames = 0

    fun start(delayFrames: Int, attackFrames: Int, holdFrames: Int, decayFrames: Int, releaseFrames: Int, sustain: Double) {
        this.delayFrames = delayFrames
        this.attackFrames = attackFrames
        this.holdFrames = holdFrames
        this.decayFrames = decayFrames
        this.releaseFrames = releaseFrames
        this.sustain = sustain
        value = 0.0
        enter(EnvelopeStage.Delay)
    }

    /** The key came up: the envelope falls from where it is. */
    fun release() {
        if (stage == EnvelopeStage.Finished) return
        stage = if (releaseFrames <= 0) EnvelopeStage.Finished else EnvelopeStage.Release
        if (stage == EnvelopeStage.Finished) value = 0.0
        stageFrames = 0
    }

    fun advance(frames: Int) {
        var left = frames
        while (left > 0) {
            when (stage) {
                EnvelopeStage.Delay -> {
                    val take = min(left, delayFrames - stageFrames)
                    stageFrames += take
                    left -= take
                    if (stageFrames >= delayFrames) enter(EnvelopeStage.Attack)
                }
                EnvelopeStage.Attack -> {
                    val take = min(left, attackFrames - stageFrames)
                    stageFrames += take
                    left -= take
                    value = stageFrames.toDouble() / attackFrames
                    if (stageFrames >= attackFrames) enter(EnvelopeStage.Hold)
                }
                EnvelopeStage.Hold -> {
                    val take = min(left, holdFrames - stageFrames)
                    stageFrames += take
                    left -= take
                    if (stageFrames >= holdFrames) enter(EnvelopeStage.Decay)
                }
                EnvelopeStage.Decay -> {
                    val rate = 1.0 / decayFrames
                    val take = min(left, ceil((value - sustain) / rate).toInt().coerceAtLeast(0))
                    value -= rate * take
                    left -= take
                    if (value <= sustain) {
                        value = sustain
                        stage = EnvelopeStage.Sustain
                    }
                }
                EnvelopeStage.Release -> {
                    value -= left.toDouble() / releaseFrames
                    left = 0
                    if (value <= 0.0) {
                        value = 0.0
                        stage = EnvelopeStage.Finished
                    }
                }
                EnvelopeStage.Sustain, EnvelopeStage.Finished -> left = 0
            }
        }
    }

    private fun enter(next: EnvelopeStage) {
        stage = next
        stageFrames = 0
        when (next) {
            EnvelopeStage.Delay -> if (delayFrames <= 0) enter(EnvelopeStage.Attack)
            EnvelopeStage.Attack -> if (attackFrames <= 0) enter(EnvelopeStage.Hold)
            EnvelopeStage.Hold -> {
                value = 1.0
                if (holdFrames <= 0) enter(EnvelopeStage.Decay)
            }
            EnvelopeStage.Decay -> if (decayFrames <= 0) {
                value = sustain
                stage = EnvelopeStage.Sustain
            }
            else -> Unit
        }
    }
}
