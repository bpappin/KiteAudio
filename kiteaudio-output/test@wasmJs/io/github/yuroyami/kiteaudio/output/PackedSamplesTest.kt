@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteaudio.output

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals

@JsFun("(block, index) => block[index]")
private external fun sampleAt(block: JsAny, index: Int): Float

class PackedSamplesTest {

    @Test
    fun aBlockFilledInOneCallHoldsEverySampleExactly() {
        val samples = floatArrayOf(0f, -0f, 1f, -1f, 0.1f, -0.333f, Float.MIN_VALUE, 1e-30f, 0.99999994f, 12345.678f)
        val block = WasmWebAudio.newBlock(samples.size)

        WasmWebAudio.fill(block, samples)

        for (i in samples.indices) {
            assertEquals(samples[i].toRawBits(), sampleAt(block, i).toRawBits(), "sample $i")
        }
    }

    @Test
    fun theBytesOfEachSampleAreLittleEndian() {
        val packed = packSamples(floatArrayOf(1f), CharArray(4))

        // 1f is 0x3F800000.
        assertEquals(listOf(0x00, 0x00, 0x80, 0x3F), packed.map { it.code })
    }
}
