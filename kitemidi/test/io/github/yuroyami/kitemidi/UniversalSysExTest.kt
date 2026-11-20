package io.github.yuroyami.kitemidi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.math.abs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UniversalSysExTest {

    @Test
    fun deviceInquiryMatchesTheSpecification() {
        assertContentEquals(bytesOf(0xF0, 0x7E, 0x7F, 0x06, 0x01, 0xF7), UniversalSysEx.deviceInquiry().toBytes())
        assertContentEquals(bytesOf(0xF0, 0x7E, 0x00, 0x06, 0x01, 0xF7), UniversalSysEx.deviceInquiry(device = 0).toBytes())
    }

    @Test
    fun masterFineTuneCentresOnFourFortyHertz() {
        assertContentEquals(bytesOf(0xF0, 0x7F, 0x7F, 0x04, 0x03, 0x00, 0x40, 0xF7), UniversalSysEx.masterFineTune(0.0).toBytes())
    }

    @Test
    fun masterFineTuneEncodesACentOffset() {
        // 32 cents flat, the offset of a track recorded at A4 = 432 Hz.
        assertContentEquals(bytesOf(0xF0, 0x7F, 0x7F, 0x04, 0x03, 0x43, 0x2B, 0xF7), UniversalSysEx.masterFineTune(-32.0).toBytes())
    }

    @Test
    fun masterFineTuneClampsToTheEndsOfItsRange() {
        assertContentEquals(bytesOf(0xF0, 0x7F, 0x7F, 0x04, 0x03, 0x00, 0x00, 0xF7), UniversalSysEx.masterFineTune(-100.0).toBytes())
        assertContentEquals(bytesOf(0xF0, 0x7F, 0x7F, 0x04, 0x03, 0x7F, 0x7F, 0xF7), UniversalSysEx.masterFineTune(100.0).toBytes())
        assertContentEquals(bytesOf(0xF0, 0x7F, 0x7F, 0x04, 0x03, 0x7F, 0x7F, 0xF7), UniversalSysEx.masterFineTune(500.0).toBytes())
    }

    @Test
    fun hertzConvertsToCentsAgainstTheReference() {
        // 432 Hz is 31.7667 cents below 440 Hz.
        val cents = UniversalSysEx.centsBetween(from = 440.0, to = 432.0)
        assertTrue(abs(cents - (-31.766653)) < 0.0001, "expected about -31.7667 cents, got $cents")
        assertTrue(abs(UniversalSysEx.centsBetween(440.0, 440.0)) < 1e-9)
        assertTrue(abs(UniversalSysEx.centsBetween(440.0, 880.0) - 1200.0) < 1e-9)
    }

    @Test
    fun masterCoarseTuneCentresOnSixtyFour() {
        assertContentEquals(bytesOf(0xF0, 0x7F, 0x7F, 0x04, 0x04, 0x00, 0x40, 0xF7), UniversalSysEx.masterCoarseTune(0).toBytes())
        assertContentEquals(bytesOf(0xF0, 0x7F, 0x7F, 0x04, 0x04, 0x00, 0x42, 0xF7), UniversalSysEx.masterCoarseTune(2).toBytes())
    }

    @Test
    fun masterVolumeUsesFourteenBits() {
        assertContentEquals(bytesOf(0xF0, 0x7F, 0x7F, 0x04, 0x01, 0x7F, 0x7F, 0xF7), UniversalSysEx.masterVolume(16383).toBytes())
    }

    @Test
    fun aDeviceInquiryReplyParses() {
        // A Yamaha reply: manufacturer 0x43, family 0x0102, member 0x0304, version 1.0.0.0
        val reply = SysEx(bytesOf(0x7E, 0x00, 0x06, 0x02, 0x43, 0x02, 0x01, 0x04, 0x03, 0x01, 0x00, 0x00, 0x00))
        val parsed = UniversalSysEx.parseDeviceInquiryReply(reply)!!
        assertEquals(0x00, parsed.deviceId)
        assertEquals(0x43, parsed.manufacturerId)
        assertEquals("Yamaha", parsed.manufacturerName)
        assertEquals(0x0102, parsed.family)
        assertEquals(0x0304, parsed.familyMember)
        assertContentEquals(listOf(1, 0, 0, 0), parsed.softwareVersion)
    }

    @Test
    fun aThreeByteManufacturerIdParses() {
        val reply = SysEx(bytesOf(0x7E, 0x00, 0x06, 0x02, 0x00, 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00))
        val parsed = UniversalSysEx.parseDeviceInquiryReply(reply)!!
        assertEquals(0x000102, parsed.manufacturerId)
    }

    @Test
    fun somethingThatIsNotAReplyParsesToNull() {
        assertNull(UniversalSysEx.parseDeviceInquiryReply(UniversalSysEx.deviceInquiry()))
        assertNull(UniversalSysEx.parseDeviceInquiryReply(SysEx(bytesOf(0x43, 0x10, 0x4C))))
    }
}
