package io.github.yuroyami.kitemidi.io.coremidi

/** macOS, a JDK that binds C (22 and later), and the framework in its place. */
public fun coreMidiAvailable(): Boolean {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return false
    if (Runtime.version().feature() < 22) return false
    return try {
        CoreMidi.getNumberOfSources
        true
    } catch (_: Throwable) {
        false
    }
}
