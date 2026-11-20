"""Verify attribution in the built jars, Android libraries and Apple frameworks, and the POM licences.

The Kotlin Toolchain puts the generated META-INF/LICENSE and META-INF/NOTICE into the JVM jar and the
jar inside the Android AAR. Klibs and sources jars cannot carry them.

Without arguments, the script checks the jars and the Android libraries. With --framework, it checks
only the given frameworks, because the macOS job builds no jar and no Android library.
"""
import argparse
import io
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--framework", action="append", default=[], type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
canonical = {name: (root / name).read_bytes() for name in ("LICENSE", "NOTICE")}
assert b"Copyright 2001-2023 Xiph.Org" in canonical["NOTICE"]
assert b"Copyright (c) 2011 Apple Inc." in canonical["NOTICE"]
failures = []
checked = 0


def verify(path, read):
    global checked
    for name, expected in canonical.items():
        try:
            actual = read(name)
        except (KeyError, FileNotFoundError):
            failures.append(f"{path}: missing {name}")
        else:
            if actual != expected:
                failures.append(f"{path}: {name} differs from the canonical text")
    checked += 1


tasks = root / "build" / "tasks"
for module in () if args.framework else ("kiteaudio-codec", "kiteaudio-codec-io"):
    jars = sorted(tasks.glob(f"_{module}_jarJvm/*.jar"))
    aars = sorted(tasks.glob(f"_{module}_aarAndroid*/*.aar"))
    if not jars or not aars:
        failures.append(f"{module}: no built jar or AAR to verify; run ./kotlin build -p jvm -p android first")
    for path in jars:
        with zipfile.ZipFile(path) as archive:
            verify(path, lambda name: archive.read(f"META-INF/{name}"))
    for path in aars:
        with zipfile.ZipFile(path) as aar:
            inner = [name for name in aar.namelist() if name.startswith("libs/") and name.endswith(".jar")]
            if not inner:
                failures.append(f"{path}: no jar under libs/")
            for name in inner:
                with zipfile.ZipFile(io.BytesIO(aar.read(name))) as archive:
                    verify(f"{path}!{name}", lambda entry: archive.read(f"META-INF/{entry}"))

# The toolchain writes the POM only when it publishes, so check the settings that it reads.
# Every library applies the Apache-2.0 entry of the library template, and the codec modules
# also apply the BSD-3-Clause entry of the codec template.
if "apache.org/licenses/LICENSE-2.0" not in (root / "library.module-template.yaml").read_text():
    failures.append("library.module-template.yaml: missing the Apache-2.0 licence")
if "bsd-3-clause" not in (root / "codec.module-template.yaml").read_text().lower():
    failures.append("codec.module-template.yaml: missing the libopus BSD-3-Clause licence")
checked += 1

for path in args.framework:
    verify(path, lambda name: (path / "Resources" / name).read_bytes())
if failures:
    raise SystemExit("Distribution attribution failed:\n" + "\n".join(failures))
print(f"Distribution attribution verified in {checked} archives, frameworks and POM settings")
