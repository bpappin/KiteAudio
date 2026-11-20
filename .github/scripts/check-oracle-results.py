"""Reject missing comparisons and unexpected skips in a full JVM CI run."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

reports = Path("build/reports/kiteaudio-codec/jvm")
required = {
    "AacDecodeOracleTest", "AdtsOracleTest", "AiffCOracleTest", "AlacOracleTest", "AlacEncodeOracleTest", "BextOracleTest", "CafOracleTest", "FfmpegOracleTest",
    "FfmpegTagOracleTest", "FlacEncodeOracleTest", "FlacOracleTest", "FlacConformanceTest",
    "IntegerEncodingOracleTest", "Mp4OracleTest", "Mp4GaplessOracleTest", "Mp4TagOracleTest", "MpegMp4OracleTest", "MpegCrcOracleTest", "MpegFreeFormatOracleTest", "MpegOracleTest", "Mp3EncodeOracleTest", "OggOpusDecodeTest",
    "OggOracleTest", "OggChainOracleTest", "OpusConformanceTest", "OpusDecodeOracleTest",
    "OpusJoinOracleTest", "OpusMultistreamOracleTest", "OpusOracleTest", "OpusEncodeOracleTest",
    "SilkDecodeOracleTest", "VorbisOracleTest", "VorbisEncodeOracleTest", "WavPackOracleTest", "WavPackEncodeOracleTest", "ApeOracleTest", "TtaOracleTest", "MusepackOracleTest", "SpeexOracleTest", "AmrOracleTest", "GsmOracleTest", "G72xOracleTest", "G722ConformanceTest", "G726ConformanceTest", "IlbcOracleTest", "DsdOracleTest", "MatroskaOracleTest", "Ac3DecodeOracleTest", "Ac3RandomOracleTest", "ResamplerOracleTest", "ChannelMixOracleTest", "LoudnessOracleTest", "OggMultiplexTagOracleTest", "VorbisFloor0OracleTest", "MutagenTagOracleTest",
    "NativeTextTagOracleTest", "NativeTextEncodingOracleTest", "R128TagOracleTest",
}
apple_only = {
    ("AiffCOracleTest", "reversedIntegerTypesAgreeWithApplesIntegerReader"),
    ("AiffCOracleTest", "appleFilesPreserveLengthSamplesAndSeeking"),
    ("CafOracleTest", "theUncompressedCafFormatsDecodeExactly"),
    ("CafOracleTest", "theCompandingLawsInCafDecodeWithinTheirAccuracy"),
    ("CafOracleTest", "quickTimeAdpcmInsideCafDecodes"),
    ("Mp4GaplessOracleTest", "appleAfconvertFilesDecodeToExactlyWhatWentIn"),
    ("AlacEncodeOracleTest", "applesAfconvertDecodesExactlyWhatWentIn"),
    ("AacDecodeOracleTest", "appleEncodedFilesDecodeAsAppleDecodesThem"),
}
# The comparisons are bit-exact against reference tools. They were qualified on Linux, and
# the Linux job runs all of them. The macOS job passes --apple-formats and runs only the
# suites that compare with Apple's own tools.
if "--apple-formats" in sys.argv[1:]:
    required = {name for name, _ in apple_only}
seen = set()
failures = []
passed = skipped = 0
# The toolchain writes one report per test engine, so group the cases by class.
suites = {}
for report in reports.glob("TEST-*.xml"):
    for case in ET.parse(report).getroot().iter("testcase"):
        suites.setdefault(case.attrib["classname"].split(".")[-1].split("[")[0], []).append(case)
for name, suite in suites.items():
    if name in required:
        seen.add(name)
        if not any(all(case.find(outcome) is None for outcome in ("skipped", "failure", "error"))
                   for case in suite):
            failures.append(f"{name}: no comparisons ran")
    if name == "FlacConformanceTest":
        manifest = Path("test-data/flac-conformance/manifest.tsv")
        vectors = [line.split("\t")[0] for line in manifest.read_text().splitlines()
                   if line and not line.startswith("#") and line.split("\t")[0].endswith(".flac")]
        cases = [case.attrib["name"] for case in suite]
        for vector in vectors:
            if not any(f"[{vector}]" in case for case in cases):
                failures.append(f"FlacConformanceTest: missing published vector {vector}")
    for case in suite:
        method = case.attrib["name"].split("[")[0]
        if case.find("skipped") is not None:
            skipped += 1
            if sys.platform == "darwin" or (name, method) not in apple_only:
                failures.append(f"{name}.{method}: unexpected skip")
        elif case.find("failure") is not None or case.find("error") is not None:
            failures.append(f"{name}.{method}: failed")
        else:
            passed += 1
failures += [f"{name}: missing test report" for name in sorted(required - seen)]
if failures:
    raise SystemExit("Oracle coverage failed:\n" + "\n".join(failures))
print(f"JVM coverage verified: {passed} passed, {skipped} explicit platform skips; all required oracle suites reported")
