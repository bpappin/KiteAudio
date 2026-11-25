package io.github.yuroyami.kiteaudio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard frames are the frames just outside a loop. The interpolation reads them at a loop boundary.
 *
 * Each font holds one sample of 96 frames at 24 kHz with the header loop 32 to 64, then the 46 zero
 * frames that SoundFont 2.04 asks for. Played at key 60 into 48 kHz, the step is half a frame, so
 * the first wrap comes after 128 output frames and each later pass takes 64. Frames 512 to 2047
 * are the steady sound of the loop.
 */
class LoopBoundaryTest {

    private val loopStart = 32
    private val loopEnd = 64
    private val sampleFrames = 96
    private val blockFrames = 2048
    private val steady = 512 until blockFrames

    /** A change of only the guard frames: 30000 more than the frames the loop would put there. */
    private val guardChange = 30_000

    // Half way between two frames, linear weighs the frame after the loop end by 1/2, and cubic
    // weighs the frame before the loop start by -1/16. The center pan sends half to each side.
    // Written as exact fractions, because a JS Float literal is not rounded to 32 bits.
    /** 0.22888184 when printed. */
    private val linearEffect = guardChange / 2f / 2f / 32768f
    /** 0.02861023 when printed. */
    private val cubicEffect = guardChange / 16f / 2f / 32768f

    private fun font(data: ShortArray, extras: List<Pair<Int, Int>> = emptyList(), sampleMode: Int = 1): SoundFont =
        KiteSynth.readFont(
            Sf2Builder().buildFont(
                sampleFrames = ShortArray(sampleFrames + 46) { if (it < sampleFrames) data[it] else 0 },
                samples = listOf(Sf2Builder.SampleSpec("loop", 0, sampleFrames, loopStart, loopEnd, 24_000, 60)),
                instruments = listOf(
                    Sf2Builder.InstrumentSpec(
                        "loop inst",
                        listOf(Sf2Builder.ZoneSpec(0, extras = listOf(Generator.SampleModes.id to sampleMode) + extras)),
                    ),
                ),
                presets = listOf(Sf2Builder.PresetDef("loop", 0, 0, listOf(Sf2Builder.PresetZoneSpec(0)))),
                info = mapOf("isng" to "EMU8000"),
            ),
        )

    /** Every frame at one level, so every guard frame matches the loop. */
    private fun flat(): ShortArray = ShortArray(sampleFrames) { 2000 }

    /** One cycle every 32 frames, so a loop of 32 frames anywhere in the sample has matching guards. */
    private fun periodic(): ShortArray = ShortArray(sampleFrames) { (2000 * sin(2 * PI * (it % 32) / 32)).roundToInt().toShort() }

    /** [data] with [guardChange] added to every frame that [outside] picks. */
    private fun changed(data: ShortArray, outside: (Int) -> Boolean): ShortArray =
        ShortArray(data.size) { if (outside(it)) (data[it] + guardChange).toShort() else data[it] }

    private fun outsideHeaderLoop(frame: Int) = frame < loopStart || frame >= loopEnd

    /** One block of key 60 at velocity 127, one voice, no reverb and no chorus. */
    private fun render(
        font: SoundFont,
        interpolation: Interpolation,
        boundary: LoopBoundary,
        releaseAt: Int? = null,
    ): FloatArray {
        val synth = Synth(font, 48_000, maxVoices = 1).apply {
            reverb.wet = 0.0
            reverb.dry = 1.0
            chorus.level = 0.0
            this.interpolation = interpolation
            loopBoundary = boundary
        }
        synth.noteOn(0, 60, 127)
        if (releaseAt != null) synth.noteOff(0, 60, atFrame = releaseAt)
        return FloatArray(blockFrames * 2).also { synth.render(it, blockFrames) }
    }

    /** The largest difference between two renders over [frames], both channels. */
    private fun difference(a: FloatArray, b: FloatArray, frames: IntRange = steady): Float {
        var most = 0f
        for (frame in frames) {
            most = max(most, abs(a[frame * 2] - b[frame * 2]))
            most = max(most, abs(a[frame * 2 + 1] - b[frame * 2 + 1]))
        }
        return most
    }

    /** How much a change of only the frames that [outside] picks moves the steady sound. */
    private fun guardEffect(
        data: ShortArray,
        interpolation: Interpolation,
        boundary: LoopBoundary,
        extras: List<Pair<Int, Int>> = emptyList(),
        outside: (Int) -> Boolean,
    ): Float = difference(
        render(font(data, extras), interpolation, boundary),
        render(font(changed(data, outside), extras), interpolation, boundary),
    )

    private fun slice(out: FloatArray, frames: IntRange): FloatArray = out.copyOfRange(frames.first * 2, (frames.last + 1) * 2)

    @Test
    fun theDefaultReadsTheGuardFrames() {
        assertEquals(LoopBoundary.GuardFrames, Synth(font(flat()), 48_000).loopBoundary)
    }

    @Test
    fun withGuardFramesAChangeOfOnlyTheGuardsMovesTheSteadySound() {
        assertEquals(linearEffect, guardEffect(flat(), Interpolation.Linear, LoopBoundary.GuardFrames, outside = ::outsideHeaderLoop))
        assertEquals(cubicEffect, guardEffect(flat(), Interpolation.Cubic, LoopBoundary.GuardFrames, outside = ::outsideHeaderLoop))
    }

    @Test
    fun wrappedIgnoresAChangeOfOnlyTheGuards() {
        for (interpolation in Interpolation.entries) {
            for (data in listOf(flat(), periodic())) {
                assertEquals(0f, guardEffect(data, interpolation, LoopBoundary.Wrapped, outside = ::outsideHeaderLoop), "$interpolation")
            }
        }
    }

    @Test
    fun matchingGuardsSoundTheSameInBothModes() {
        for (interpolation in Interpolation.entries) {
            for (data in listOf(flat(), periodic())) {
                val font = font(data)
                assertContentEquals(
                    render(font, interpolation, LoopBoundary.GuardFrames),
                    render(font, interpolation, LoopBoundary.Wrapped),
                    "$interpolation",
                )
            }
        }
    }

    @Test
    fun wrappedFollowsALoopMovedEarlierByTheZone() {
        // Offsets of -8 make the loop 24 to 56. The periodic data matches its guards there too.
        val offsets = listOf(Generator.StartLoopAddrsOffset.id to -8, Generator.EndLoopAddrsOffset.id to -8)
        val plan = font(periodic(), offsets).voicesFor(0, 0, 60, 127).single()
        assertEquals(24, plan.loopStartFrame)
        assertEquals(56, plan.loopEndFrame)
        val outsideLoop = { frame: Int -> frame < 24 || frame >= 56 }
        for (interpolation in Interpolation.entries) {
            val font = font(periodic(), offsets)
            assertContentEquals(
                render(font, interpolation, LoopBoundary.GuardFrames),
                render(font, interpolation, LoopBoundary.Wrapped),
                "$interpolation",
            )
            assertEquals(0f, guardEffect(periodic(), interpolation, LoopBoundary.Wrapped, offsets, outsideLoop), "$interpolation")
        }
        assertEquals(linearEffect, guardEffect(periodic(), Interpolation.Linear, LoopBoundary.GuardFrames, offsets, outsideLoop))
        assertEquals(cubicEffect, guardEffect(periodic(), Interpolation.Cubic, LoopBoundary.GuardFrames, offsets, outsideLoop))
    }

    @Test
    fun wrappedFollowsALoopMovedLaterByTheZone() {
        // Offsets of +16 make the loop 48 to 80. The change leaves the header guards matching and
        // breaks only the frames after the zone's loop end, so a check of the header loop sees nothing.
        val offsets = listOf(Generator.StartLoopAddrsOffset.id to 16, Generator.EndLoopAddrsOffset.id to 16)
        val plan = font(periodic(), offsets).voicesFor(0, 0, 60, 127).single()
        assertEquals(48, plan.loopStartFrame)
        assertEquals(80, plan.loopEndFrame)
        val afterLoop = { frame: Int -> frame >= 80 }
        assertEquals(linearEffect, guardEffect(periodic(), Interpolation.Linear, LoopBoundary.GuardFrames, offsets, afterLoop))
        // Cubic already wraps the taps after the loop end, and the frame before the loop start did not change.
        assertEquals(0f, guardEffect(periodic(), Interpolation.Cubic, LoopBoundary.GuardFrames, offsets, afterLoop))
        for (interpolation in Interpolation.entries) {
            assertEquals(0f, guardEffect(periodic(), interpolation, LoopBoundary.Wrapped, offsets, afterLoop), "$interpolation")
        }
    }

    @Test
    fun wrappedLeavesTheAttackBeforeTheFirstWrapAlone() {
        val font = font(changed(flat(), ::outsideHeaderLoop))
        // The voice starts at frame 0 and reaches the loop end after this many output frames.
        val firstWrap = loopEnd * 2
        for (interpolation in Interpolation.entries) {
            val guards = render(font, interpolation, LoopBoundary.GuardFrames)
            val wrapped = render(font, interpolation, LoopBoundary.Wrapped)
            assertContentEquals(slice(guards, 0 until firstWrap), slice(wrapped, 0 until firstWrap), "$interpolation")
            // The modes part at the loop start for cubic and at the loop end for linear, after the wrap.
            assertTrue(difference(guards, wrapped, firstWrap until blockFrames) > 0f, "$interpolation never left the attack")
        }
    }

    @Test
    fun wrappedLeavesTheTailOfAReleasedLoopAlone() {
        // Released 20 output frames into the eleventh pass, at frame 42 of the loop. The voice
        // leaves the loop 44 output frames later and plays frames 64 to 95 through its release.
        val release = 128 + 64 * 10 + 20
        val leaves = release + (loopEnd - 42) * 2
        val tailEnd = leaves + (sampleFrames - loopEnd) * 2
        val font = font(changed(flat(), ::outsideHeaderLoop), listOf(Generator.ReleaseVolEnv.id to 0), sampleMode = 3)
        for (interpolation in Interpolation.entries) {
            val guards = render(font, interpolation, LoopBoundary.GuardFrames, releaseAt = release)
            val wrapped = render(font, interpolation, LoopBoundary.Wrapped, releaseAt = release)
            assertTrue(difference(guards, wrapped, steady.first until release) > 0f, "$interpolation: the loop itself should differ")
            assertContentEquals(slice(guards, leaves until blockFrames), slice(wrapped, leaves until blockFrames), "$interpolation")
            // The last pass reads the frame after the loop end as the tail's first frame, so it matches from the release on.
            assertContentEquals(slice(guards, release until leaves), slice(wrapped, release until leaves), "$interpolation: the last pass")
            // The tail is the changed data after the loop, so it is loud, and the voice ends with the sample.
            assertTrue(abs(wrapped[(leaves + 4) * 2]) > 0.3f, "$interpolation: the tail is missing")
            assertTrue(slice(wrapped, tailEnd + 2 until blockFrames).all { it == 0f }, "$interpolation: the voice did not end")
        }
    }

    @Test
    fun aLoopTooShortForTheCubicTapsStaysInsideTheSample() {
        // Loops of two and three frames, one at each end of the sample: the taps reach past both ends of the loop.
        val loops = listOf(0 to 2, 0 to 3, 93 to 96, 94 to 96)
        val flatLevel = render(font(flat()), Interpolation.Linear, LoopBoundary.GuardFrames)[1000 * 2]
        assertTrue(flatLevel > 0f)
        for ((start, end) in loops) {
            val offsets = listOf(
                Generator.StartLoopAddrsOffset.id to start - loopStart,
                Generator.EndLoopAddrsOffset.id to end - loopEnd,
            )
            val font = font(flat(), offsets)
            for (interpolation in Interpolation.entries) {
                for (boundary in LoopBoundary.entries) {
                    val out = render(font, interpolation, boundary)
                    assertTrue(out.all { it.isFinite() }, "$start to $end, $interpolation, $boundary")
                    assertTrue(abs(out[(blockFrames - 1) * 2]) > 0f, "$start to $end, $interpolation, $boundary: the loop stopped")
                }
                // Wrapped reads only frames of the loop once it wraps, and every one of them is at the flat level.
                // The weights of the band-limited kernel sum to 1 only to within a few parts in a million.
                val wrapped = render(font, interpolation, LoopBoundary.Wrapped)
                val tolerance = if (interpolation == Interpolation.BandLimited) flatLevel * 1e-5f else 0f
                for (frame in steady) assertEquals(flatLevel, wrapped[frame * 2], tolerance, "$start to $end, $interpolation, frame $frame")
            }
        }
    }

    @Test
    fun aSampleWithNoUsableLoopPlaysOnceInBothModes() {
        // A loop of one frame and a loop of none both play the sample once, without looping.
        for ((start, end) in listOf(40 to 41, 40 to 40)) {
            val offsets = listOf(
                Generator.StartLoopAddrsOffset.id to start - loopStart,
                Generator.EndLoopAddrsOffset.id to end - loopEnd,
            )
            val font = font(changed(flat(), ::outsideHeaderLoop), offsets)
            for (interpolation in Interpolation.entries) {
                val guards = render(font, interpolation, LoopBoundary.GuardFrames)
                val wrapped = render(font, interpolation, LoopBoundary.Wrapped)
                assertContentEquals(guards, wrapped, "$start to $end, $interpolation")
                assertTrue(slice(wrapped, steady).all { it == 0f }, "$start to $end, $interpolation: the sample looped")
            }
        }
    }
}
