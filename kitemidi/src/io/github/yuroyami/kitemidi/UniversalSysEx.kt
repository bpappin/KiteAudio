package io.github.yuroyami.kitemidi

import kotlin.math.ln
import kotlin.math.roundToInt

/** What an instrument answered when asked what it is. */
public data class DeviceInquiryReply(
    public val deviceId: Int,
    public val manufacturerId: Int,
    public val family: Int,
    public val familyMember: Int,
    public val softwareVersion: List<Int>,
) {
    /** The manufacturer's name when this library knows the number, otherwise null. */
    public val manufacturerName: String? get() = MANUFACTURERS[manufacturerId]

    private companion object {
        val MANUFACTURERS = mapOf(
            0x41 to "Roland",
            0x42 to "Korg",
            0x43 to "Yamaha",
            0x44 to "Casio",
            0x47 to "Akai",
            0x000E to "Alesis",
            0x00_00_01 to "Sequential",
            0x00_20_29 to "Focusrite or Novation",
            0x00_21_09 to "Native Instruments",
        )
    }
}

/**
 * The messages every manufacturer agrees on.
 *
 * Universal system exclusive is the part of system exclusive that is not brand-specific. It is
 * how you ask an instrument what it is, and how you change its tuning without knowing who made
 * it. Real time messages open with 0x7F and take effect at once; non real time messages open
 * with 0x7E.
 */
public object UniversalSysEx {

    /** 0x7F addresses every device on the cable. */
    public const val ALL_DEVICES: Int = 0x7F

    /**
     * Asks the instrument to say what it is. The reply comes back as another system exclusive
     * message, which [parseDeviceInquiryReply] reads.
     */
    public fun deviceInquiry(device: Int = ALL_DEVICES): SysEx {
        checkDevice(device)
        return SysEx(byteArrayOf(0x7E, device.toByte(), 0x06, 0x01))
    }

    /**
     * Moves the instrument's reference pitch by [cents], where 0.0 means A4 at 440 Hz.
     *
     * The message carries fourteen bits over a range of plus or minus 100 cents, so a value
     * outside that is clamped rather than wrapped.
     */
    public fun masterFineTune(cents: Double, device: Int = ALL_DEVICES): SysEx {
        checkDevice(device)
        val raw = (CENTRE + (cents / 100.0) * CENTRE).roundToInt().coerceIn(0, 0x3FFF)
        return SysEx(byteArrayOf(0x7F, device.toByte(), 0x04, 0x03, (raw and 0x7F).toByte(), ((raw shr 7) and 0x7F).toByte()))
    }

    /** Moves the instrument's reference pitch by whole semitones, plus or minus 64. */
    public fun masterCoarseTune(semitones: Int, device: Int = ALL_DEVICES): SysEx {
        checkDevice(device)
        require(semitones in -64..63) { "master coarse tune covers -64 to 63 semitones, got $semitones" }
        return SysEx(byteArrayOf(0x7F, device.toByte(), 0x04, 0x04, 0x00, (0x40 + semitones).toByte()))
    }

    /** Sets the instrument's overall volume, 0 to 16383. */
    public fun masterVolume(level: Int, device: Int = ALL_DEVICES): SysEx {
        checkDevice(device)
        require(level in 0..16383) { "master volume covers 0 to 16383, got $level" }
        return SysEx(byteArrayOf(0x7F, device.toByte(), 0x04, 0x01, (level and 0x7F).toByte(), ((level shr 7) and 0x7F).toByte()))
    }

    /**
     * How far [to] sits from [from], in cents. Negative means flatter.
     *
     * This is what turns "the track is at A4 = 432 Hz and the piano is at 440" into the number
     * [masterFineTune] wants.
     */
    public fun centsBetween(from: Double, to: Double): Double {
        require(from > 0.0 && to > 0.0) { "a frequency must be above zero, got from=$from to=$to" }
        return 1200.0 * (ln(to / from) / ln(2.0))
    }

    /** Reads a reply to [deviceInquiry], or returns null when [sysex] is something else. */
    public fun parseDeviceInquiryReply(sysex: SysEx): DeviceInquiryReply? {
        val d = sysex.data
        if (d.size < 4) return null
        if ((d[0].toInt() and 0xFF) != 0x7E) return null
        if ((d[2].toInt() and 0xFF) != 0x06 || (d[3].toInt() and 0xFF) != 0x02) return null

        var index = 4
        if (index >= d.size) return null
        // A manufacturer number is one byte, unless it is zero, which introduces a three-byte one.
        val manufacturer: Int
        if ((d[index].toInt() and 0xFF) == 0x00) {
            if (index + 2 >= d.size) return null
            manufacturer = ((d[index + 1].toInt() and 0xFF) shl 8) or (d[index + 2].toInt() and 0xFF)
            index += 3
        } else {
            manufacturer = d[index].toInt() and 0xFF
            index += 1
        }
        if (index + 3 >= d.size) return null
        val family = ((d[index + 1].toInt() and 0xFF) shl 8) or (d[index].toInt() and 0xFF)
        val member = ((d[index + 3].toInt() and 0xFF) shl 8) or (d[index + 2].toInt() and 0xFF)
        index += 4
        val version = (index until minOf(index + 4, d.size)).map { d[it].toInt() and 0xFF }

        return DeviceInquiryReply(
            deviceId = d[1].toInt() and 0xFF,
            manufacturerId = manufacturer,
            family = family,
            familyMember = member,
            softwareVersion = version,
        )
    }

    private const val CENTRE = 8192.0

    private fun checkDevice(device: Int) {
        require(device in 0..0x7F) { "a device number must be 0 to 127, got $device" }
    }
}
