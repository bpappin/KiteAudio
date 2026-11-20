package io.github.yuroyami.kitesynth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModulatorsTest {

    @Test
    fun fallingConcaveVelocityRunsFromOneToZero() {
        assertEquals(0.0, sourceCurve(0x0502, 127))
        assertEquals(1.0, sourceCurve(0x0502, 0))
    }

    @Test
    fun aBipolarPanIsCentredOnSixtyFour() {
        assertTrue(abs(sourceCurve(0x028A, 64)) < 0.01)
        assertEquals(-1.0, sourceCurve(0x028A, 0))
        assertEquals(1.0, sourceCurve(0x028A, 127))
    }

    @Test
    fun theModWheelIsAStraightLine() {
        assertEquals(1.0, sourceCurve(0x0081, 127))
        assertEquals(0.0, sourceCurve(0x0081, 0))
        assertTrue(abs(sourceCurve(0x0081, 64) - 64 / 127.0) < 1e-9)
    }

    @Test
    fun aSwitchFlipsHalfWay() {
        assertEquals(0.0, sourceCurve(0x0C81, 63))
        assertEquals(1.0, sourceCurve(0x0C81, 64))
    }

    @Test
    fun concaveRisesSlowlyThenFastAndConvexTheOtherWay() {
        val concave = sourceCurve(0x0481, 64)
        val convex = sourceCurve(0x0881, 64)
        assertTrue(concave < 0.3, "concave at the middle is $concave")
        assertTrue(convex > 0.7, "convex at the middle is $convex")
    }

    @Test
    fun thePitchWheelReadsItsFourteenBits() {
        assertEquals(1.0, sourceCurve(0x020E, 16383))
        assertEquals(-1.0, sourceCurve(0x020E, 0))
    }

    @Test
    fun theDefaultsAreTheFormatsNineWithTheWheelBuiltIn() {
        assertEquals(9, DefaultModulators.size)
        val velocity = DefaultModulators.first()
        assertEquals(0x0502, velocity.source)
        assertEquals(Generator.InitialAttenuation, velocity.destination)
        assertEquals(960, velocity.amount)
    }
}
