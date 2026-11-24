#!/usr/bin/env python3
"""Offline Android control-flow checks using production source and deterministic framework stubs.

Requires JDK 21 and cached Kotlin compiler dependencies. No Gradle or Android runtime is used.
The only source transformations remove expect/actual and copy common factory defaults.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
p.add_argument('--kotlin-version', default='2.4.21')
a = p.parse_args()
java = str(Path(a.java_home) / 'bin/java') if a.java_home else shutil.which('java')
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
def jar(module, version):
    return next(j for j in (cache / module / version).glob('*/*.jar') if not j.name.endswith(('-sources.jar', '-javadoc.jar')))
stdlib = jar('org.jetbrains.kotlin/kotlin-stdlib', a.kotlin_version)
compiler = [jar('org.jetbrains.kotlin/' + module, a.kotlin_version) for module in ('kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-reflect')]
compiler += [jar('org.jetbrains.intellij.deps/trove4j', '1.0.20200330'), jar('org.jetbrains/annotations', '13.0'), jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', '1.8.1')]
with tempfile.TemporaryDirectory(prefix='kiteaudio-output-android-regression-') as directory:
    work = Path(directory)
    common = (ROOT / 'kiteaudio-output/src/io/github/yuroyami/kiteaudio/output/Audio.kt').read_text()
    marker = '/**\n * Opens an output for this platform.\n'
    assert common.count(marker) == 1
    default = '    preferredFormat: AudioFormat = AudioFormat(48_000, ChannelLayout.STEREO),\n'
    assert common.count(default) == 1
    (work / 'Audio.kt').write_text(common.split(marker)[0])
    backend = (ROOT / 'kiteaudio-output/src@android/io/github/yuroyami/kiteaudio/output/Audio.android.kt').read_text()
    assert backend.count('public actual fun') == 2
    backend = backend.replace('public actual fun', 'public fun')
    signature = '    preferredFormat: AudioFormat,\n    bufferFrames: Int,\n    onEvent: (AudioOutputEvent) -> Unit,'
    assert backend.count(signature) == 1
    # The Android backend does not import ChannelLayout, so the copied default names its package.
    backend = backend.replace(signature, '    preferredFormat: AudioFormat = AudioFormat(48_000, io.github.yuroyami.kiteaudio.core.ChannelLayout.STEREO),\n    bufferFrames: Int = 1024,\n    onEvent: (AudioOutputEvent) -> Unit = {},')
    (work / 'Audio.android.kt').write_text(backend)
    for source in HERE.glob('*.kt'):
        shutil.copy2(source, work / source.name)
    # The output API uses the AudioFormat and ChannelLayout types of kiteaudio-core.
    (work / 'core').mkdir()
    for source in (ROOT / 'kiteaudio-core/src').rglob('*.kt'):
        shutil.copy2(source, work / 'core' / source.name)
    subprocess.run([java, '-Xmx512m', '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '21', '-classpath', str(stdlib), '-d', str(work), *map(str, work.glob('*.kt')), *map(str, (work / 'core').glob('*.kt'))], check=True, timeout=60)
    subprocess.run([java, '-cp', os.pathsep.join((str(work), str(stdlib))), 'RegressionKt'], check=True, timeout=20)
