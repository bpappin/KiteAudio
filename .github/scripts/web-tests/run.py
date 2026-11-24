#!/usr/bin/env python3
"""Run the JS, wasmJs and wasmWasi tests in Node.

`./kotlin test` cannot run the JS and wasmWasi tests. It runs the wasmJs tests only in a browser, with a
30-second limit that the malformed-input sweeps exceed, and a browser has no filesystem for the file tests.
This script links the tests with the toolchain, then runs them: JS through runner.mjs, and wasmJs and
wasmWasi through the test entry point that the module exports.
"""
import argparse
import glob
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
PLATFORMS = ("js", "wasmJs", "wasmWasi")
# Each module with web tests, and the web platforms it builds for.
MODULES = {
    "kiteaudio-core": PLATFORMS,
    "kiteaudio-codec": PLATFORMS,
    "kiteaudio-codec-io": PLATFORMS,
    "kiteaudio-midi": ("js", "wasmJs"),
    "kiteaudio-output": ("js", "wasmJs"),
    "kiteaudio-synth": ("js", "wasmJs"),
}
ARTIFACTS = ROOT / "build/artifacts/CompiledWebArtifact"
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")


def node():
    """Node from PATH, else the one the Kotlin Toolchain downloaded."""
    found = shutil.which("node")
    if found:
        return found
    downloaded = sorted(glob.glob(str(CACHE / "extract.cache/*node-v*/bin/node")))
    if downloaded:
        return downloaded[-1]
    raise RuntimeError(f"No node executable on PATH or in {CACHE}")


def run(*args, capture=False, env=None):
    print("Running " + " ".join(str(a) for a in args[:3]) + (" ..." if len(args) > 3 else ""), flush=True)
    result = subprocess.run([str(a) for a in args], cwd=ROOT, text=True, check=not capture, env=env,
                            stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None)
    return result


def kotlin(*args):
    run(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"), *args)


def js_tests(module, executable):
    kotlin("task", f":{module}:linkJsTest")
    entry = ARTIFACTS / f"{module}jsTestrelease/kotlin-output/{module}_test.mjs"
    run(executable, HERE / "runner.mjs", entry)


def wasm_tests(module, platform, executable):
    task = platform[0].upper() + platform[1:]
    kotlin("task", f":{module}:link{task}TestDebug")
    entry = ARTIFACTS / f"{module}{platform}Testdebug/kotlin-output/{module}_test.mjs"
    script = f"const tests = await import({entry.as_uri()!r}); tests.startUnitTests();"
    preload, env = [], None
    if module == "kiteaudio-codec-io" and platform == "wasmWasi":
        # The file tests need a preopened directory, which the Kotlin WASI launcher does not give.
        directory = ROOT / "build/wasi-test-files"
        directory.mkdir(parents=True, exist_ok=True)
        env = {**os.environ, "AUDIOKODEC_WASI_TEST_DIRECTORY": str(directory)}
        preload = ["--require", ROOT / "kiteaudio-codec-io/test-tools/wasi-filesystem.cjs"]
    result = run(executable, *preload, "--input-type=module", "-e", script, capture=True, env=env)
    # kotlin-test for Wasm reports in the TeamCity service message format.
    output = result.stdout
    finished = len(re.findall(r"##teamcity\[testFinished ", output))
    failed = re.findall(r"##teamcity\[testFailed name='([^']*)' message='([^']*)'", output)
    ignored = len(re.findall(r"##teamcity\[testIgnored ", output))
    for name, message in failed:
        print(f"Failed {name}: {message}", flush=True)
    print(f"{module} {platform}: {finished - len(failed)} passed, {len(failed)} failed, {ignored} ignored", flush=True)
    if result.returncode != 0 or finished == 0:
        print(output[-4000:], flush=True)
    if result.returncode != 0 or failed or finished == 0:
        raise RuntimeError(f"{platform} tests of {module} failed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=PLATFORMS, action="append",
                        help="run one platform; repeat it for more (default: every one a module builds for)")
    parser.add_argument("--module", choices=MODULES, action="append",
                        help="run one module; repeat it for more (default: all of them)")
    args = parser.parse_args()
    platforms = args.platform or PLATFORMS
    modules = args.module or MODULES
    # A module named on the command line that builds for none of the platforms asked for would
    # run nothing, and a run that tests nothing must not pass.
    idle = [module for module in modules if not any(platform in MODULES[module] for platform in platforms)]
    if args.module and idle:
        raise RuntimeError(f"{', '.join(idle)} has no tests on {', '.join(platforms)}; "
                           f"it builds for {', '.join(p for m in idle for p in MODULES[m])}")
    if len(idle) == len(modules):
        raise RuntimeError(f"no module builds for {', '.join(platforms)}")
    executable = node()
    for module in modules:
        for platform in platforms:
            if platform not in MODULES[module]:
                continue
            if platform == "js":
                js_tests(module, executable)
            else:
                wasm_tests(module, platform, executable)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
