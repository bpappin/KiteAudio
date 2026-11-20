package io.github.yuroyami.kiteaudio.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FontRetentionTest {
    @Test
    fun fontReplacementClearsEveryInactiveVoicesSampleAndPlanReferences() {
        val original = KiteSynth.readFont(Sf2Builder().build())
        val synth = Synth(original, maxVoices = 2)
        synth.noteOn(0, 60, 127)
        synth.render(FloatArray(32), 16)
        val voices = Synth::class.java.getDeclaredField("voices").apply { isAccessible = true }.get(synth) as Array<*>
        val pool = Voice::class.java.getDeclaredField("pool").apply { isAccessible = true }
        val plan = Voice::class.java.getDeclaredField("plan").apply { isAccessible = true }
        assertSame(original.samples.single().data, pool.get(voices[0]))
        synth.font = KiteSynth.readFont(Sf2Builder().build())
        assertEquals(0, synth.activeVoices)
        for (voice in voices) {
            assertNull(plan.get(voice))
            assertTrue((pool.get(voice) as ShortArray).isEmpty())
            assertFalse(pool.get(voice) === original.samples.single().data)
        }
    }
}
