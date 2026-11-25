package io.github.yuroyami.kiteaudio.synth

import kotlin.math.log2
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * A synthesiser.
 *
 * Give it a font, feed it MIDI, ask it for samples.
 *
 * ```kotlin
 * val synth = Synth(font, sampleRate = 48_000)
 * synth.noteOn(channel = 0, key = 60, velocity = 100, atFrame = 0)
 * synth.render(buffer, frames = 256)          // interleaved stereo
 * ```
 *
 * **Events are stamped in frames, not in time.** A note due half way through a block is applied
 * half way through the block, not at its start. Anything else quantises every note to the block
 * size, which at 256 frames is 5 ms of slop that a player can hear as sloppiness.
 *
 * **A linked stereo pair plays as one.** When a left and a right sample link to each other and both
 * zones play the note, both sides start together at the right side's pitch, each with its own level
 * and pan. Any other link plays each zone as its own voice, at its own pitch.
 *
 * **Nothing here talks to a MIDI library.** Messages arrive as their raw status and data bytes,
 * which every source can produce, so this stays a library about sound rather than about MIDI.
 */
public class Synth @Throws(SoundFontFormatException::class, IllegalArgumentException::class) public constructor(
    font: SoundFont,
    public val sampleRate: Int = 48_000,
    public val maxVoices: Int = 256,
    /** Cumulative preset/instrument regions allowed during construction and font replacement. */
    public val maxPreparedRegions: Int,
    /** Cumulative modulator entries retained by prepared voice plans, after default replacement. */
    public val maxPreparedModulators: Int = DEFAULT_PREPARED_MODULATORS,
    /** Candidate preset/instrument zone pairs across the font, including disjoint ranges. */
    public val maxPreparedPairs: Long = DEFAULT_PREPARED_PAIRS,
) {
    /**
     * Allows 16,384 regions, 1,048,576 modulator entries and 4,194,304 candidate zone pairs.
     * Larger trusted fonts can supply explicit nonnegative budgets.
     */
    @Throws(SoundFontFormatException::class, IllegalArgumentException::class)
    public constructor(font: SoundFont, sampleRate: Int = 48_000, maxVoices: Int = 256) :
        this(font, sampleRate, maxVoices, DEFAULT_PREPARED_REGIONS)

    init {
        require(sampleRate > 0) { "sample rate must be above zero, got $sampleRate" }
        require(maxVoices > 0) { "there must be room for at least one voice, got $maxVoices" }
    }

    private var preparedFont: PreparedSoundFont =
        PreparedSoundFont.build(font, maxPreparedRegions, maxPreparedModulators, maxPreparedPairs)

    private var loadedFont: SoundFont = font

    /**
     * The loaded font. Assignment can throw during preparation, leaving the old font playing.
     * Swift uses [currentFont] and throwing [replaceFont], because a property setter cannot
     * return an NSError across the Objective-C boundary.
     */
    @OptIn(ExperimentalObjCRefinement::class)
    @HiddenFromObjC
    public var font: SoundFont
        get() = loadedFont
        set(value) { replaceFont(value) }

    /** The loaded font, including for Swift callers where [font] is hidden. */
    public fun currentFont(): SoundFont = loadedFont

    /**
     * Prepares and replaces the font as one control-thread operation. Failure leaves the
     * current font and its voices intact; success stops them before installing the replacement.
     */
    @Throws(SoundFontFormatException::class, IllegalArgumentException::class)
    public fun replaceFont(newFont: SoundFont) {
        val replacement = PreparedSoundFont.build(newFont, maxPreparedRegions, maxPreparedModulators, maxPreparedPairs)
        // Room for the new font's linked modulators before any of its plans can start, so a note allocates nothing.
        for (voice in voices) voice.reserveLinks(replacement.linkRoom)
        allSoundOff()
        loadedFont = newFont
        preparedFont = replacement
    }

    private val voices = Array(maxVoices) { Voice().also { it.reserveLinks(preparedFont.linkRoom) } }

    /** The smallest link room among the voices, for tests. */
    internal val linkRoom: Int get() = voices.minOf { it.linkRoom }

    /**
     * The room the sound is in.
     *
     * A dry sample sounds like a recording of a piano in a padded box. Set [Reverb.wet] to zero
     * to hear the samples on their own.
     */
    public val reverb: Reverb = Reverb.room(sampleRate)

    /** The chorus a voice's send and CC 93 feed. */
    public val chorus: Chorus = Chorus(sampleRate)

    /**
     * How voices read between frames. Linear is there for a test, or a device too slow for cubic.
     * The first choice of [Interpolation.BandLimited] builds its kernel table, so make it on the control thread.
     */
    public var interpolation: Interpolation = Interpolation.Cubic
        set(value) {
            if (value == Interpolation.BandLimited) bandLimitedKernel = SincKernel.shared
            field = value
        }

    /** The table that [Interpolation.BandLimited] reads through, once a caller has chosen it. */
    private var bandLimitedKernel: SincKernel? = null

    /**
     * How voices read the frames next to a loop boundary. [LoopBoundary.GuardFrames] reads the
     * font's guard frames, as SoundFont 2.04 describes. [LoopBoundary.Wrapped] reads the loop as a
     * circle after the first wrap, for a font whose loops click or sound different from what its
     * author heard. Like [interpolation], each render reads it, so a change also reaches the notes that sound.
     */
    public var loopBoundary: LoopBoundary = LoopBoundary.GuardFrames

    // What the voices send to the room and the chorus, one block at a time. Allocated once.
    private val reverbBus = FloatArray(BUS_FRAMES * 2)
    private val chorusBus = FloatArray(BUS_FRAMES * 2)
    private val channels = Array(16) { ChannelState(percussion = it == PERCUSSION_CHANNEL) }
    private var nextAge = 0L

    // Events waiting for their frame, kept as parallel arrays so queueing one allocates nothing.
    private val eventFrames = IntArray(MAX_QUEUED)
    private val eventStatus = IntArray(MAX_QUEUED)
    private val eventData1 = IntArray(MAX_QUEUED)
    private val eventData2 = IntArray(MAX_QUEUED)
    private var eventCount = 0

    /** Number of events rejected by the full queue, saturating at Long.MAX_VALUE. */
    public var rejectedEvents: Long = 0
        private set

    /** How many voices are sounding. */
    public val activeVoices: Int get() = voices.count { it.active }

    /** Which keys are sounding, for a keyboard on screen and for knowing what was stolen. */
    public val soundingKeys: List<Int> get() = voices.filter { it.active }.map { it.key }.distinct()

    /** A4's frequency. Changing it retunes every melodic note that starts afterwards. Drums stay put. */
    public var referenceA4: Double = 440.0

    /**
     * Cents to add per key, so a temperament is one array. A note keeps what it started with.
     *
     * Index by MIDI key. All zeroes is equal temperament, which is where it starts.
     */
    public val tuningCents: DoubleArray = DoubleArray(128)

    private class ChannelState(var percussion: Boolean) {
        /** The last value of every controller. Bank select is 0 and 32; the rest mean what MIDI says. */
        val controllers = IntArray(128)
        val bank: Int get() = controllers[0]
        var program = 0
        var sustain = false
        var sostenuto = false
        var pitchBend = 0            // -8192 to 8191
        var bendRangeSemitones = 2.0
        var fineTuneCents = 0.0
        var coarseTuneSemitones = 0
        /** True once an RPN is selected, false once an NRPN is: data entry only ever reaches an RPN. */
        var registered = false
        var channelPressure = 0
        val polyPressure = IntArray(128)
        val heldByPedal = BooleanArray(128)
        val heldBySostenuto = BooleanArray(128)
        val keyDown = BooleanArray(128)

        init {
            defaults()
        }

        /** Where a channel starts. Bank select survives it, as it does on every synthesiser. */
        fun defaults() {
            val bankMsb = controllers[0]
            val bankLsb = controllers[32]
            controllers.fill(0)
            controllers[0] = bankMsb
            controllers[32] = bankLsb
            // Full volume, not MIDI's 100: an instrument nobody set a level on plays at full.
            controllers[7] = 127
            controllers[10] = 64
            controllers[11] = 127
            // The whole room, as a full volume is the default: a channel nobody set a send on sounds as it always did.
            controllers[91] = 127
            for (number in 98..101) controllers[number] = 127
            pitchBend = 0
            bendRangeSemitones = 2.0
            fineTuneCents = 0.0
            coarseTuneSemitones = 0
            registered = false
            channelPressure = 0
            polyPressure.fill(0)
        }
    }

    // ------------------------------------------------------------------ channels

    /**
     * Makes a channel play drum kits from bank 128, or stop doing so. Channel 9 starts as one, as
     * General MIDI says. Like every setting here, change it between renders.
     */
    public fun setPercussion(channel: Int, percussion: Boolean) {
        channels[channel].percussion = percussion
    }

    public fun isPercussion(channel: Int): Boolean = channels[channel].percussion

    /** The bank a program change on this channel reads, as the last bank select left it. */
    public fun bankOn(channel: Int): Int = channels[channel].bank

    public fun programOn(channel: Int): Int = channels[channel].program

    /** What a note on this channel would play now, or null on a percussion channel with no kit. */
    public fun presetOn(channel: Int): Preset? = presetFor(channels[channel])?.source

    /** The last value a controller was set to on this channel, 0 to 127. */
    public fun controller(channel: Int, controller: Int): Int = channels[channel].controllers[controller]

    /** How far a full pitch bend reaches, as RPN 0 last set it. Two semitones until then. */
    public fun bendRangeSemitones(channel: Int): Double = channels[channel].bendRangeSemitones

    public fun channelPressure(channel: Int): Int = channels[channel].channelPressure

    public fun polyPressure(channel: Int, key: Int): Int = channels[channel].polyPressure[key]

    // ------------------------------------------------------------------ queueing

    /**
     * Queues a raw MIDI message to take effect [atFrame] frames into the next render.
     *
     * Negative frames are clamped to zero. A full queue rejects the new event without changing
     * any accepted event's timestamp or order. Use [tryEnqueue] when rejection should not throw.
     * A rejected note-off needs caller recovery, for example [allSoundOff].
     *
     * @throws IllegalStateException if all 1,024 event slots are occupied.
     */
    @Throws(IllegalStateException::class)
    public fun enqueue(status: Int, data1: Int, data2: Int, atFrame: Int) {
        check(tryEnqueue(status, data1, data2, atFrame)) { "the event queue is full; render pending events or call allSoundOff before retrying" }
    }

    /** Tries to queue an event without allocating or reordering earlier events; false means it was rejected. */
    public fun tryEnqueue(status: Int, data1: Int, data2: Int, atFrame: Int): Boolean {
        if (eventCount >= MAX_QUEUED) {
            if (rejectedEvents != Long.MAX_VALUE) rejectedEvents++
            return false
        }
        eventFrames[eventCount] = atFrame.coerceAtLeast(0)
        eventStatus[eventCount] = status
        eventData1[eventCount] = data1
        eventData2[eventCount] = data2
        eventCount++
        return true
    }

    /** Starts a note. A velocity of zero is a release, as it is everywhere else in MIDI. */
    @Throws(IllegalStateException::class)
    public fun noteOn(channel: Int, key: Int, velocity: Int, atFrame: Int = 0) {
        enqueue(0x90 or (channel and 0x0F), key, velocity, atFrame)
    }

    /** Ends a note. */
    @Throws(IllegalStateException::class)
    public fun noteOff(channel: Int, key: Int, atFrame: Int = 0) {
        enqueue(0x80 or (channel and 0x0F), key, 0, atFrame)
    }

    /** A control change: sustain is 64, volume 7, expression 11. */
    @Throws(IllegalStateException::class)
    public fun controlChange(channel: Int, controller: Int, value: Int, atFrame: Int = 0) {
        enqueue(0xB0 or (channel and 0x0F), controller, value, atFrame)
    }

    /** Chooses the sound for a channel. */
    @Throws(IllegalStateException::class)
    public fun programChange(channel: Int, program: Int, atFrame: Int = 0) {
        enqueue(0xC0 or (channel and 0x0F), program, 0, atFrame)
    }

    /** Bends the pitch. The value runs from -8192 to 8191, with zero in the middle. */
    @Throws(IllegalStateException::class)
    public fun pitchBend(channel: Int, value: Int, atFrame: Int = 0) {
        val shifted = (value + 8192).coerceIn(0, 16383)
        enqueue(0xE0 or (channel and 0x0F), shifted and 0x7F, (shifted shr 7) and 0x7F, atFrame)
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Fills [out] with [frames] frames of interleaved stereo.
     *
     * Overwrites rather than adds, so a caller does not have to clear the buffer first.
     * Allocates nothing.
     */
    @Throws(IllegalArgumentException::class)
    public fun render(out: FloatArray, frames: Int) {
        require(frames >= 0 && frames <= out.size / 2) {
            "the buffer holds ${out.size} values, which is not room for $frames stereo frames"
        }
        for (i in 0 until frames * 2) out[i] = 0f

        var blockStart = 0
        while (blockStart < frames) {
            val blockEnd = minOf(frames, blockStart + BUS_FRAMES)
            val blockFrames = blockEnd - blockStart
            reverbBus.fill(0f, 0, blockFrames * 2)
            chorusBus.fill(0f, 0, blockFrames * 2)
            var done = blockStart
            while (done < blockEnd) {
                applyEventsAt(done)
                val next = nextEventFrame(from = done, limit = blockEnd)
                val chunk = next - done
                // Volume, expression and pan reach each voice through its modulators.
                val cubic = interpolation == Interpolation.Cubic
                val wrapped = loopBoundary == LoopBoundary.Wrapped
                val kernel = if (interpolation == Interpolation.BandLimited) bandLimitedKernel else null
                for (voice in voices) {
                    voice.renderInto(
                        out, reverbBus, chorusBus, done * 2, (done - blockStart) * 2, chunk,
                        cubic = cubic, wrapped = wrapped, kernel = kernel,
                    )
                }
                done = next
            }
            // The room and the chorus go on after the voices, so they hear everything played in this block.
            reverb.process(reverbBus, out, blockStart, blockFrames)
            chorus.process(chorusBus, out, blockStart, blockFrames)
            blockStart = blockEnd
        }

        // Anything left was stamped past this block. Move it back so it lands next time.
        var kept = 0
        for (i in 0 until eventCount) {
            if (eventFrames[i] >= frames) {
                eventFrames[kept] = eventFrames[i] - frames
                eventStatus[kept] = eventStatus[i]
                eventData1[kept] = eventData1[i]
                eventData2[kept] = eventData2[i]
                kept++
            }
        }
        eventCount = kept
    }

    private fun nextEventFrame(from: Int, limit: Int): Int {
        var soonest = limit
        for (i in 0 until eventCount) {
            val at = eventFrames[i]
            if (at in from until soonest) soonest = at
        }
        return soonest
    }

    /**
     * Applies everything stamped at this frame, in the order it arrived.
     *
     * Order inside one timestamp is load-bearing in MIDI: a note-off has to land before the
     * note-on that follows it, or the new note is released the instant it starts. Removing by
     * swapping the last event into the gap is cheaper and gets that backwards, which only shows
     * once three events share a frame.
     */
    private fun applyEventsAt(frame: Int) {
        var write = 0
        for (i in 0 until eventCount) {
            if (eventFrames[i] == frame) {
                apply(eventStatus[i], eventData1[i], eventData2[i])
                continue
            }
            if (write != i) {
                eventFrames[write] = eventFrames[i]
                eventStatus[write] = eventStatus[i]
                eventData1[write] = eventData1[i]
                eventData2[write] = eventData2[i]
            }
            write++
        }
        eventCount = write
    }

    // ------------------------------------------------------------------ messages

    private fun apply(status: Int, data1: Int, data2: Int) {
        val channel = status and 0x0F
        when (status and 0xF0) {
            0x80 -> stopNote(channel, data1)
            0x90 -> if (data2 == 0) stopNote(channel, data1) else startNote(channel, data1, data2)
            0xB0 -> control(channel, data1, data2)
            0xC0 -> channels[channel].program = data1 and 0x7F
            0xA0 -> if (data1 in 0..127) {
                channels[channel].polyPressure[data1] = data2 and 0x7F
                remodulate(channel) { it.key == data1 && it.watchesPolyPressure }
            }
            0xD0 -> {
                channels[channel].channelPressure = data1 and 0x7F
                remodulate(channel) { it.watchesPressure }
            }
            0xE0 -> {
                channels[channel].pitchBend = ((data2 shl 7) or data1) - 8192
                // A bend has to move notes that are already sounding, not only the next one.
                retune(channel)
                remodulate(channel) { it.watchesWheel }
            }
            else -> Unit
        }
    }

    private fun startNote(channel: Int, key: Int, velocity: Int) {
        if (key !in 0..127) return
        val state = channels[channel]
        // A key struck again while still held replaces itself rather than stacking.
        stopNote(channel, key, force = true)
        state.keyDown[key] = true

        val preset = presetFor(state) ?: return
        val regions = preset.regions
        val fixed = tableCents(state, key)
        val tuning = bendCents(channel) + channelCents(state) + fixed

        // Every cut happens before any of this note's voices exist. A drum written as two layers
        // puts both zones in one exclusive class, and cutting as we go kills the layer this same
        // note-on started a moment earlier.
        var regionIndex = 0
        while (regionIndex < regions.size) {
            val region = regions[regionIndex]
            if (region.matches(key, velocity) && region.plan.exclusiveClass != 0) {
                cutExclusive(channel, region.plan.exclusiveClass)
            }
            regionIndex++
        }
        regionIndex = 0
        while (regionIndex < regions.size) {
            val region = regions[regionIndex]
            if (region.matches(key, velocity)) {
                val voice = freeVoice()
                if (voice != null) {
                    voice.startPrepared(
                        plan = region.plan,
                        key = key,
                        velocity = velocity,
                        sampleRate = sampleRate,
                        channel = channel,
                        age = nextAge++,
                        tuningCents = tuning,
                        fixedCents = fixed,
                        controllers = state.controllers,
                        pitchWheel = state.pitchBend,
                        channelPressure = state.channelPressure,
                        polyPressure = state.polyPressure[key],
                        bendRange = state.bendRangeSemitones,
                        // Both sides of a stereo pair start here, on this frame, at the right side's pitch.
                        pitchPlan = preset.pitchPlan(regionIndex, key, velocity),
                    )
                }
            }
            regionIndex++
        }
    }

    /** Kits live on bank 128. A melodic miss tries bank 0, then the font's first preset. */
    private fun presetFor(state: ChannelState): PreparedPreset? = if (state.percussion) {
        preparedFont.preset(PERCUSSION_BANK, state.program) ?: preparedFont.preset(PERCUSSION_BANK, 0)
    } else {
        preparedFont.preset(state.bank, state.program)
            ?: preparedFont.preset(0, state.program)
            ?: preparedFont.fallback
    }

    /** The tuning a note keeps from its start: the table and A4. A drum gets none. */
    private fun tableCents(state: ChannelState, key: Int): Double {
        // A quarter-tone table is for melodies. A retuned snare is just a worse snare.
        if (state.percussion) return 0.0
        val reference = if (referenceA4 > 0.0 && referenceA4 != 440.0) 1200.0 * log2(referenceA4 / 440.0) else 0.0
        return tuningCents[key] + reference
    }

    /** The tuning that follows the channel while a note sounds: RPN fine and coarse tune. */
    private fun channelCents(state: ChannelState): Double =
        if (state.percussion) 0.0 else state.fineTuneCents + state.coarseTuneSemitones * 100.0

    private fun stopNote(channel: Int, key: Int, force: Boolean = false) {
        if (key < 0 || key > 127) return
        val state = channels[channel]
        if (!force) {
            state.keyDown[key] = false
            // Sostenuto holds what was down when it went down. The sustain pedal holds everything.
            if (state.sostenuto && state.heldBySostenuto[key]) return
            if (state.sustain) {
                state.heldByPedal[key] = true
                return
            }
        }
        state.heldByPedal[key] = false
        for (voice in voices) {
            if (voice.active && voice.channel == channel && voice.key == key && !voice.releasing) {
                voice.release()
            }
        }
    }

    private fun control(channel: Int, controller: Int, value: Int) {
        if (controller !in 0..127) return
        val state = channels[channel]
        val amount = value and 0x7F
        when (controller) {
            120 -> return allSoundOff(channel)
            121 -> return resetControllers(channel)
            123 -> return allNotesOff(channel)
        }
        state.controllers[controller] = amount
        when (controller) {
            6 -> {
                // A new coarse value starts its fine value again, as MIDI's 14-bit controllers do.
                state.controllers[38] = 0
                dataEntry(channel)
            }
            38 -> dataEntry(channel)
            64 -> sustainPedal(channel, amount >= 64)
            66 -> sostenutoPedal(channel, amount >= 64)
            98, 99 -> state.registered = false
            100, 101 -> state.registered = true
        }
        remodulate(channel) { it.watches(controller) }
    }

    /** Runs the modulators again on the voices of [channel] that [moved] says a change reaches. */
    private inline fun remodulate(channel: Int, moved: (Voice) -> Boolean) {
        val state = channels[channel]
        var index = 0
        while (index < voices.size) {
            val voice = voices[index]
            if (voice.active && voice.channel == channel && moved(voice)) {
                voice.modulate(state.controllers, state.pitchBend, state.channelPressure, state.polyPressure[voice.key], state.bendRangeSemitones)
            }
            index++
        }
    }

    /** Data entry lands on the selected RPN: 0 is the bend range, 1 fine tune, 2 coarse tune. */
    private fun dataEntry(channel: Int) {
        val state = channels[channel]
        if (!state.registered) return
        val msb = state.controllers[6]
        val lsb = state.controllers[38]
        when ((state.controllers[101] shl 7) or state.controllers[100]) {
            0 -> state.bendRangeSemitones = msb + lsb / 100.0
            1 -> state.fineTuneCents = (((msb shl 7) or lsb) - 8192) / 8192.0 * 100.0
            2 -> state.coarseTuneSemitones = msb - 64
            else -> return
        }
        retune(channel)
        remodulate(channel) { it.watchesWheel }
    }

    private fun sustainPedal(channel: Int, down: Boolean) {
        val state = channels[channel]
        if (state.sustain && !down) {
            // The pedal came up: everything it was holding is released now.
            state.sustain = false
            var key = 0
            while (key < 128) {
                if (state.heldByPedal[key]) {
                    state.heldByPedal[key] = false
                    stopNote(channel, key)
                }
                key++
            }
        }
        state.sustain = down
    }

    /** Holds the keys that are down as it goes down, and only those. Keys played later are free. */
    private fun sostenutoPedal(channel: Int, down: Boolean) {
        val state = channels[channel]
        if (down == state.sostenuto) return
        state.sostenuto = down
        var key = 0
        while (key < 128) {
            if (down) {
                state.heldBySostenuto[key] = state.keyDown[key]
            } else if (state.heldBySostenuto[key]) {
                state.heldBySostenuto[key] = false
                if (!state.keyDown[key]) stopNote(channel, key)
            }
            key++
        }
    }

    /** Cuts other notes in the same exclusive class, the way a closed hi-hat cuts an open one. */
    private fun cutExclusive(channel: Int, exclusiveClass: Int) {
        for (voice in voices) {
            if (voice.active && voice.channel == channel && voice.exclusiveClass == exclusiveClass) {
                voice.kill()
            }
        }
    }

    /**
     * A voice to use, stealing one if every slot is busy.
     *
     * Prefers a voice already fading, then the quietest, then the oldest. Stealing the loudest
     * or the newest is what makes a synthesiser sound broken under load: the note you just
     * played is the one that disappears.
     */
    private fun freeVoice(): Voice? {
        voices.firstOrNull { !it.active }?.let { return it }

        var best: Voice? = null
        var bestAudibility = 0.0
        for (voice in voices) {
            val previous = best
            val audibility = voice.audibility
            if (previous == null || (voice.releasing && !previous.releasing) ||
                (voice.releasing == previous.releasing &&
                    (audibility < bestAudibility ||
                        (audibility == bestAudibility && voice.age < previous.age)))
            ) {
                best = voice
                bestAudibility = audibility
            }
        }
        best?.kill()
        return best
    }

    private fun bendCents(channel: Int): Double {
        val state = channels[channel]
        if (state.pitchBend == 0) return 0.0
        return state.pitchBend / 8192.0 * state.bendRangeSemitones * 100.0
    }

    /**
     * Pitch bend on notes that are already sounding.
     *
     * A voice works out its step once at start, so a bend has to be pushed into it. Only the
     * step changes; the position keeps its place, so a bend does not restart the note.
     */
    private fun retune(channel: Int) {
        val state = channels[channel]
        val bend = bendCents(channel)
        var index = 0
        while (index < voices.size) {
            val voice = voices[index]
            if (voice.active && voice.channel == channel) {
                voice.retune(bend + channelCents(state) + voice.fixedCents)
            }
            index++
        }
    }

    // ------------------------------------------------------------------ panic

    /** Stops everything at once, on every channel. */
    public fun allSoundOff() {
        for (voice in voices) voice.kill()
        // Without this the last note of one song rings on into the first note of the next.
        reverb.clear()
        chorus.clear()
        for (state in channels) {
            state.sustain = false
            state.sostenuto = false
            state.controllers[64] = 0
            state.controllers[66] = 0
            state.heldByPedal.fill(false)
            state.heldBySostenuto.fill(false)
            state.keyDown.fill(false)
        }
        eventCount = 0
    }

    /** Stops everything on one channel at once. */
    public fun allSoundOff(channel: Int) {
        for (voice in voices) if (voice.channel == channel) voice.kill()
        val state = channels[channel]
        state.heldByPedal.fill(false)
        state.heldBySostenuto.fill(false)
        state.keyDown.fill(false)
    }

    /** Releases every note on a channel, letting them fade rather than cutting them. */
    public fun allNotesOff(channel: Int) {
        val state = channels[channel]
        state.sustain = false
        state.sostenuto = false
        state.controllers[64] = 0
        state.controllers[66] = 0
        state.heldByPedal.fill(false)
        state.heldBySostenuto.fill(false)
        state.keyDown.fill(false)
        for (voice in voices) {
            if (voice.active && voice.channel == channel) voice.release()
        }
    }

    /** Puts a channel's controllers, pedals, bend and tuning back where they started. Program and bank stay. */
    public fun reset(channel: Int) {
        releasePedalledNotes(channel)
        // The sends are a mix, not a performance: a reset leaves the room and the chorus where they were.
        val room = channels[channel].controllers[91]
        val chorusSend = channels[channel].controllers[93]
        channels[channel].defaults()
        channels[channel].controllers[91] = room
        channels[channel].controllers[93] = chorusSend
        retune(channel)
        remodulate(channel) { true }
    }

    /** Reset All Controllers, as General MIDI means it: volume, pan, bank, program and the sends stay. */
    private fun resetControllers(channel: Int) {
        releasePedalledNotes(channel)
        val state = channels[channel]
        state.pitchBend = 0
        state.controllers[1] = 0
        state.controllers[11] = 127
        for (pedal in 64..67) state.controllers[pedal] = 0
        for (number in 98..101) state.controllers[number] = 127
        state.registered = false
        state.channelPressure = 0
        state.polyPressure.fill(0)
        retune(channel)
        remodulate(channel) { true }
    }

    /** Lets go of both pedals: a note one was holding after its key came up starts to fade. */
    private fun releasePedalledNotes(channel: Int) {
        val state = channels[channel]
        var index = 0
        while (index < voices.size) {
            val voice = voices[index]
            // Active first: a voice that never started has no key to look up.
            if (voice.active && voice.channel == channel && !state.keyDown[voice.key] &&
                (state.heldByPedal[voice.key] || state.heldBySostenuto[voice.key])
            ) {
                voice.release()
            }
            index++
        }
        state.sustain = false
        state.sostenuto = false
        state.heldByPedal.fill(false)
        state.heldBySostenuto.fill(false)
    }

    private companion object {
        /** Room for a busy bar of a dense piece, plus space. */
        const val MAX_QUEUED = 1024

        /** The longest stretch the send buses cover; a longer render goes through in pieces. */
        const val BUS_FRAMES = 1024

        /** General MIDI's drum channel, counted from zero. */
        const val PERCUSSION_CHANNEL = 9

        /** Where every SoundFont keeps its drum kits. */
        const val PERCUSSION_BANK = 128
    }
}

internal const val DEFAULT_PREPARED_REGIONS: Int = 16_384
internal const val DEFAULT_PREPARED_MODULATORS: Int = 1_048_576
internal const val DEFAULT_PREPARED_PAIRS: Long = 4_194_304L
