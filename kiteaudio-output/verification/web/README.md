# Web verification

A browser page for what the web tests cannot check: that the Web Audio output plays through a real browser.

## Link the test modules

The page loads exports from the linked web test modules, so link them first, from the repository root:

```sh
./kotlin task :kiteaudio-output:linkJsTest
./kotlin task :kiteaudio-output:linkWasmJsTestRelease
```

## In a browser

Serve the repository root and open the page from localhost, which a browser treats as a secure context:

```sh
python3 -m http.server 8765 --bind 127.0.0.1
```

Then open `http://localhost:8765/kiteaudio-output/verification/web/index.html` and choose a technique: Auto, Worklet or Buffers. Each Play button opens an output by it, plays a quiet 440 Hz tone for three seconds and stops it from inside the renderer, then prints the latency the output started with and every underrun, overrun and failure. Listen as well: the report cannot hear a click.

The Play buttons are clicks, so the browser lets them play at once. To check a start made before any click, open the page as `index.html?early=js` or `index.html?early=wasmJs`, optionally with `&mode=Buffers`. The page starts an output as it loads, which should wait without playing, and the Play button for that platform starts the same output again, which should play. A browser that remembers earlier visits may let the first start play at once.

To see the fallback to buffers, open the page from another machine on the network over plain HTTP, which is not a secure context, and play with Auto. For a hidden tab, switch away during a run. Browsers slow page timers in a hidden tab, so a Buffers run is expected to report underruns there and a Worklet run none, but neither has been checked yet.

`globalThis.kiteAudioOutputReport` receives the playback report, so the page can be driven from a browser's own automation as well.
