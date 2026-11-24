@file:OptIn(ExperimentalWasmJsInterop::class, ExperimentalJsExport::class)

package io.github.yuroyami.kiteaudio.output

import kotlin.js.ExperimentalJsExport
import kotlin.js.ExperimentalWasmJsInterop

@JsFun("(text) => { globalThis.kiteAudioOutputReport(text); }")
private external fun reportToPage(text: String)

private var again: (() -> Unit)? = null

/**
 * [browserPlaybackCheck] through a browser output that plays by the [BrowserTechnique] named [mode],
 * reporting to `globalThis.kiteAudioOutputReport`, for verification/web.
 */
@JsExport
public fun kiteAudioOutputCheck(seconds: Int, mode: String) {
    again = browserPlaybackCheck(
        seconds,
        { format, frames, onEvent -> openBrowserOutput(WasmWebAudio, format, frames, BrowserTechnique.valueOf(mode), onEvent) },
        ::reportToPage,
    )
}

/** Starts the output of the last [kiteAudioOutputCheck] again, as a click handler would after a start made before the click. */
@JsExport
public fun kiteAudioOutputAgain() {
    again?.invoke()
}
