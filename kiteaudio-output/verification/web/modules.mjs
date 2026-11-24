// Loads the exports the verification page needs from the linked web test modules.
// Each takes a platform, "js" or "wasmJs", and the repository root as a URL.

const ARTIFACTS = "build/artifacts/CompiledWebArtifact";

// kotlin-test registers Kotlin/JS tests through describe and it when they exist. Defining them as
// no-ops means importing a test file runs no tests.
globalThis.describe ??= () => {};
globalThis.it ??= () => {};

function js(root, module, file) {
  const dir = module.replace("kiteaudio-", "kiteaudio_");
  return new URL(`${ARTIFACTS}/${module}jsTestrelease/kotlin-output/kotlin_${dir}_test/io/github/yuroyami/kiteaudio/${file}.export.mjs`, root);
}

function wasm(root, module) {
  return new URL(`${ARTIFACTS}/${module}wasmJsTestrelease/kotlin-output/${module}_test.mjs`, root);
}

/**
 * { check, again } on [platform]. check(seconds, mode) plays, mode being Auto, Worklet or Buffers, and reports to
 * globalThis.kiteAudioOutputReport; again() starts the last check's output again.
 */
export async function playbackCheck(platform, root) {
  const url = platform === "js" ? js(root, "kiteaudio-output", "output/BrowserPlaybackExport") : wasm(root, "kiteaudio-output");
  const module = await import(url.href);
  return { check: module.kiteAudioOutputCheck, again: module.kiteAudioOutputAgain };
}
