package io.github.yuroyami.kiteaudio.output

import io.github.yuroyami.kiteaudio.core.AudioFormat

/**
 * What the Apple output asks the system about devices. macOS asks the CoreAudio hardware API, and
 * iOS asks the audio session. Tests use a fake.
 */
internal interface AppleOutputHardware {
    /** The output devices now. */
    fun devices(): List<AudioOutputDevice>

    /** The format that the device with the id [id] runs at, or the default device for null. Null when unknown. */
    fun deviceFormat(id: String?): AudioFormat?

    /**
     * True when the output sets a chosen device on its queue, which is macOS. On iOS the system
     * chooses the route, and the output only checks that the chosen device is the current route.
     */
    val setsQueueDevice: Boolean
}

internal expect fun platformOutputHardware(): AppleOutputHardware

/** The rates that [AudioFormatPolicy.Nearest] can try inside a range of nominal rates from [minimum] to [maximum]. */
internal fun ratesInRange(minimum: Double, maximum: Double): List<Int> =
    if (minimum == maximum) listOf(minimum.toInt()).filter { it > 0 }
    else DEFAULT_CANDIDATE_RATES.filter { it >= minimum && it <= maximum }
