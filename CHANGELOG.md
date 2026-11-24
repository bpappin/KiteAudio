# Changelog

## Unreleased 0.3.0

The first release of KiteAudio. KiteAudio holds the codec, MIDI, synth and output
libraries in one repository. Each library is its own artifact, and all seven artifacts
have version 0.3.0. No earlier version of any of them is on Maven Central.

### Changed in the merge

The artifacts and packages have new names:

| Artifact before | Artifact now | Package now |
|---|---|---|
| `io.github.yuroyami:audiokodec` | `io.github.yuroyami:kiteaudio-codec` | `io.github.yuroyami.kiteaudio.codec` |
| `io.github.yuroyami:audiokodec-io` | `io.github.yuroyami:kiteaudio-codec-io` | `io.github.yuroyami.kiteaudio.codec.io` |
| `io.github.yuroyami:kitemidi` | `io.github.yuroyami:kiteaudio-midi` | `io.github.yuroyami.kiteaudio.midi` |
| `io.github.yuroyami:kitemidi-io` | `io.github.yuroyami:kiteaudio-midi-devices` | `io.github.yuroyami.kiteaudio.midi.devices` |
| `io.github.yuroyami:kitemidi-io-coremidi` | `io.github.yuroyami:kiteaudio-midi-coremidi` | `io.github.yuroyami.kiteaudio.midi.coremidi` |
| `io.github.yuroyami:kitesynth` | `io.github.yuroyami:kiteaudio-synth` | `io.github.yuroyami.kiteaudio.synth` |
| `io.github.yuroyami:kitert` | `io.github.yuroyami:kiteaudio-output` | `io.github.yuroyami.kiteaudio.output` |

- The public types and objects keep their names, for example `AudioKodec`, `KiteMidi`,
  `KiteSynth` and `AudioKodecException`. The `AUDIOKODEC_*` environment variables and the
  Apple framework names also stay.
- The klib names follow the artifact names, for example `io.github.yuroyami:kiteaudio-codec`.
- The temporary files of the codec use the prefix `kiteaudio-codec-`. The benchmark reads the
  system property `kiteaudio-codec.benchmark.cache`.
- The MIDI corpus test reads its folder from the environment variable `KITEAUDIO_MIDI_CORPUS`.

### Changed in the MIDI module

- `SequenceNumber`, `ChannelPrefix`, `PortPrefix`, `SmpteOffset` and `TempoEntry` reject values that a
  Standard MIDI File cannot store. They throw `IllegalArgumentException`, and the message names the range.
- `TimeSignature` also rejects a `clocksPerClick` or a `thirtySecondsPerQuarter` outside 0 to 255. Before,
  the writer cut these values to one byte.
- `SmpteOffset` has a new last parameter, `framesPerSecond`, with a default of 24. It takes the values of
  `Division.Smpte.framesPerSecond`, and the file stores it in bits 5 and 6 of the hours byte.
- The reader keeps a meta event whose values the typed model cannot hold as `UnknownMeta` with its raw
  bytes, so the file loads and the event writes back unchanged. Before, a tempo of 0, a key signature
  of more than 7 sharps or a time signature that `TimeSignature` rejects failed the file. A channel prefix
  above 15 lost its high bits, and a key signature mode above 1 was written back as 1.
- A payload that is too short still fails with `MidiFormatException`.
- `TempoMap.microsToTick` can no longer divide by zero, because `TempoEntry` rejects a tempo of 0.
- `MidiOutput.send` and `MidiOutput.sendRaw` on a closed output throw `MidiPortException`, also for an
  empty array. Before, the JVM backends dropped the bytes, and the Apple backend ignored an empty array
  before it checked for close. This applies to the JVM, Android, Apple and CoreMIDI for the JVM backends.
- No `MidiInput` callback starts after `close()` returns. `close()` waits for a callback that already
  runs on another thread. Before, a callback could still start just after `close()` returned.
- On the JVM `javax.sound.midi` backend, a port of a device that is unplugged while open is lost. Its
  `send` and `sendRaw` throw `MidiPortException`, and its input delivers nothing. Before, a handle could
  stay on the dead device, and after one of two identical devices was unplugged, a new open of the
  other one could reach that dead device.
- When a group of identical devices on the JVM gets smaller, every open port of that group is lost. The
  JDK gives no way to tell identical devices apart, so this can also end a port whose device still works.

### Added in the codec

The uncompressed family end to end, plus FLAC, Vorbis, MPEG audio and Ogg.


- Read and write WAV, including WAVE_FORMAT_EXTENSIBLE, RF64 and BW64.
- Read and write AIFF and AIFF-C, and Sun AU.
- Read Wave64.
- Decode integer PCM at 8, 16, 24 and 32 bits in both byte orders, IEEE float
  at 32 and 64 bits, A-law, mu-law, IMA ADPCM in the WAV and QuickTime block
  layouts, and Microsoft ADPCM.
- Encode all of those except QuickTime IMA.
- Float32 PCM model with an exact integer view for lossless codings, sample
  format conversion, TPDF dither, interleaving and channel remapping.
- Pull-based streaming decoder with sample-accurate seeking.
- Header-only probing.
- A sniffer that names 29 audio formats.
- Guards for truncated, corrupt and hostile input, with a typed exception
  hierarchy and an allocation ceiling.
- Tag reading on any file, decodable or not: ID3v2.2, 2.3 and 2.4, ID3v1 and
  v1.1, APEv1 and APEv2, Lyrics3v2, RIFF `LIST INFO`, Broadcast Wave `bext`,
  AIFF text chunks, and an ID3v2 chunk inside a RIFF or AIFF file.
- A merged tag view with format precedence, alongside every block exactly as
  it was stored.
- Tag writing for ID3v2.3 and 2.4, RIFF `LIST INFO` and AIFF text chunks, with
  the audio payload copied byte for byte.
- Pictures, chapters, numbered genres, ReplayGain and the Opus R128
  convention.
- FLAC, decoded bit-exactly at 8 to 32 bits and encoded at 8, 16 or 24, with
  sample-accurate seeking with or without a seek table, and verification against
  the MD5 the encoding program stored.
- Apple Core Audio Format, read, including packet-table priming and remainder
  trimming, and named channel layouts reordered into AudioKodec's channel
  order.
- The Ogg container, read in full: pages and checksums, packet reassembly
  across pages, several logical streams at once, chained streams and granule
  positions. Vorbis, Opus, FLAC and Speex inside Ogg all decode and seek.
- MP4, M4A, M4B, 3GP and QuickTime, read: the box tree, sample tables, a
  `moov` before or after the audio, fragmented files, edit lists, and
  chapters from a chapter track or a Nero `chpl` list. ALAC, Opus, FLAC and
  uncompressed audio inside decode and seek, and so do AMR and AC-3.
- MP4 item lists, read and written: the standard atoms, freeform items,
  track, disc, tempo and compilation numbers, ReplayGain and cover art, with
  unmodelled items kept and only the `moov` box rebuilt. Changed chapters are
  written as a Nero `chpl` list.
- The iTunes `iTunSMPB` gapless record applied when decoding an MP4 file
  whose edit list does not trim it.
- Apple Lossless encoding into `.m4a` at 16, 20, 24 and 32 bits with one to
  eight channels, translated from Apple's reference encoder and writing the
  same packets, with tags, cover art and chapters.
- Opus encoding into Ogg with `EncodeTarget.opus`: the CELT music mode at full
  bandwidth, mono and stereo, 10 or 20 ms frames, at a constant, constrained
  variable or variable (the default) bit rate from 6 to 510 kbit/s.
  Translated from libopus 1.5.2's CELT encoder and writing the same packets.
  The pre-skip and end trim make the decoded length exactly the input's,
  other sample rates are resampled to 48 kHz, and tags go into the OpusTags
  header.
- Ogg Vorbis encoding, the first lossy encoder, written from the Vorbis I
  specification: `EncodeTarget.vorbis(quality)` on oggenc's 0 to 10 scale,
  one to eight channels in Vorbis's speaker order at 8 to 192 kHz, block
  switching at onsets, a masking model, floor 1, residue types 1 and 2 and
  lossless stereo coupling, with tags in the comment header. It streams to any
  sink, and libvorbisfile, ffmpeg and AudioKodec decode exactly the input's
  length. At the default quality a music-like test signal takes 145 kbit/s and
  scores an `opus_compare` error of 0.38 against libvorbisfile's decode.
- MP3 encoding with `EncodeTarget.mp3(bitrate)` and `EncodeTarget.mp3Vbr(quality)`,
  written from ISO/IEC 11172-3: MPEG-1 Layer III, mono or stereo at 32, 44.1 and
  48 kHz, constant bitrates from 32 to 320 kbit/s and a variable bitrate by
  quality 0 to 9, with psychoacoustic model 2, block switching at onsets, the
  Annex C quantization loops, the bit reservoir and mid-side stereo chosen
  frame by frame. An `Info` or `Xing` frame with the LAME extension makes the
  files gapless in AudioKodec, ffmpeg and libmpg123, and tags go into an
  ID3v2.4 tag. On a music-like test signal, 192 kbit/s scores an
  `opus_compare` error of 0.27 against ffmpeg's decode, where LAME scores
  0.29, and the default variable quality 0.27 at 179 kbit/s.
- A shared Ogg page writer, a forward MDCT and a codec-neutral psychoacoustic
  toolkit (Bark and ERB scales, the absolute threshold of hearing, spreading,
  tonality, masking thresholds and a transient detector) for the encoders that
  follow.
- Raw AAC in ADTS and ADIF framing, probed: rate, channels and bitrate, and
  for ADTS the exact length from its frame headers.
- AAC-LC, decoded from MP4 and ADTS: every tool of the low complexity
  profile, every sampling rate, fixed and program channel configurations
  reordered into AudioKodec's channel order, edit-list trimming and
  sample-exact seeking. Written from ISO/IEC 14496-3 in floating point and
  agreeing with ffmpeg's decoder to within float rounding. HE-AAC, AAC Main,
  SSR and LTP, 960-sample frames and coupling channels are refused by name.
- WavPack, decoded: every encoding mode at 8 to 32 bits and 32-bit float,
  mono to multichannel in AudioKodec's speaker order, exact integers through
  `IntPcmAccess`, block checksums verified, and exact seeking. Hybrid files
  decode to their lossy stream. Translated from WavPack 5.9.0 and identical
  to `wvunpack`. DSD inside WavPack is refused by name.
- WavPack, encoded losslessly at 8, 16, 24 and 32 bits with one to eight
  channels in the fast, normal, high and very high modes, with the RIFF
  header, MD5 sum and APEv2 tags the reference writes. Translated from
  WavPack 5.9.0, and byte-identical to `wavpack -m --no-threads`. Tags of
  WavPack files can be rewritten.
- Musepack, decoded: stream version 8 at every quality, mono and stereo,
  with mid/side coding and noise substitution, the exact length its header
  states, and seeking. Translated from libmpcdec, BSD-licensed, and on the
  JVM identical to it float for float. Stream version 7 and older are refused
  by name.
- GSM 06.10, decoded from WAV files in Microsoft's layout and from
  QuickTime and MP4 in the plain one, translated from libgsm in its
  fixed-point arithmetic and identical to it sample for sample on every
  target, with seeking.
- iLBC, decoded in both frame sizes from `.lbc` files in the storage format
  of RFC 3952 and from CAF and QuickTime, translated from WebRTC in its
  fixed-point arithmetic and identical to it sample for sample on every
  target, with exact seeking.
- Tag writing into Ogg files that interleave several logical streams: the
  first audio stream's headers are rebuilt in place and every other
  stream's pages are copied byte for byte, in their original order.
- `Levels` for peak, RMS and clipping scans, silence at the ends, gain and
  linear or logarithmic fades, and `Signals` for sine, square, noise and
  silence.
- Waveform data, `WaveformReducer`: per-bucket minimum, maximum and RMS
  of each channel, pushed a block at a time and pulled in bounded memory,
  identical however the audio is split.
- Loudness measurement, `LoudnessMeter`: integrated, momentary and
  short-term loudness, loudness range and sample and true peak as EBU R 128
  defines them, translated from libebur128 1.2.6 and matching it, with
  ReplayGain 2 track and album values for the tag writers.
- Channel mixing between layouts, `Pcm.mixChannels`, with the downmix
  coefficients of ITU-R BS.775 and ffmpeg's rules for the positions BS.775
  leaves out, refusing a conversion with no defined answer; per-channel gain
  and mid-side conversion.
- Sample rate conversion: `Resampler`, streaming and allocation-free, and
  `Pcm.resample` for a whole buffer. The default mode is speexdsp 1.2.1's
  windowed sinc resampler at quality 10, translated sample for sample, with
  the output aligned to the input; a straight-line mode serves previews.
- DSD, read from DSF and DSDIFF files and converted to PCM at a
  thirty-second of its rate through three linear-phase decimating filters,
  stated in full, with channels in AudioKodec's order, the ID3v2 tags of
  both containers read, and exact seeking. Agrees with ffmpeg's conversion
  tone by tone within the filters' difference. DST compression is refused
  by name.
- Matroska and WebM, read from RFC 9559: Opus, Vorbis, FLAC, MPEG audio,
  AAC-LC, AC-3 and PCM from the first audio track, with a length counted from
  the frames and trimmed by CodecDelay and DiscardPadding that agrees with
  ffmpeg's decode, exact seeking, elements of unknown size, all three lacing
  schemes, zlib-compressed and header-stripped frames, tags and chapters.
  Encrypted tracks are refused by name.
- AC-3, decoded from raw `.ac3` streams, MP4 and Matroska: the three sampling
  rates, every coding mode from dual mono to 3/2 with the low frequency
  channel, all three exponent strategies, the parametric bit allocation with
  delta bit allocation, channel coupling with phase flags and master
  coordinates, rematrixing, block switching channel by channel, dynamic range
  words, CRC checks, and exact seeking. Written from ATSC A/52:2012 in float.
  It agrees with ffmpeg's decoder to within the rounding of that decoder's
  fixed-point coefficients, and exactly once that rounding is reproduced, on
  encoder-written files and on frames that use every element of the format.
  Enhanced AC-3 is refused by name.
- G.722 and G.726, decoded from WAV and AU files, written from the ITU
  Recommendations and exact on every one of the ITU's test sequences, with
  exact seeking. `DecodeOptions.g722Mode` selects G.722's mode 2 or 3.
- AMR-NB and AMR-WB, decoded at every rate, from `.amr` and `.awb` files,
  multichannel ones included, and from 3GP and MP4: comfort noise,
  concealment of frames without data, AMR-WB decoder homing, and seeking.
  Translated from opencore-amr, Apache-licensed, in its fixed-point
  arithmetic, and identical to it sample for sample on every target.
- Speex inside Ogg, decoded: narrowband, wideband and ultra-wideband at every
  quality, variable bitrate, discontinuous transmission and in-band stereo,
  to the samples and length `speexdec` gives, and seeking. Translated from
  libspeex 1.2.1, BSD-licensed, and on the JVM identical to its float build
  float for float.
- True Audio, decoded: 8 to 24 bits including 20-bit audio, any number of
  channels, exact integers through `IntPcmAccess`, every CRC verified, and
  exact seeking. Translated from the format authors' BSD-licensed decoder
  and identical to both `tta -d` and ffmpeg. Password-protected files are
  refused by name.
- Monkey's Audio, decoded: every compression level from fast to insane, 8 to
  32 bits and 32-bit float, one to eight channels in AudioKodec's speaker
  order except seven, exact integers through `IntPcmAccess`, frame checksums
  verified, and exact seeking. Translated from the Monkey's Audio SDK 13.27
  and identical to `mac -d`. Files from before version 3.99 are refused by
  name.
- Apple Lossless, decoded from MP4 and CAF at 16, 20, 24 and 32 bits with one
  to eight channels, exactly, with integers through `IntPcmAccess`.
- Opus, decoded: SILK, CELT and the hybrid of the two, the redundant frames
  that smooth a change of codec, and every channel mapping,
  surround in AudioKodec's channel order and ambisonics with or without a
  demixing matrix. Pre-skip, end trim and the header's output gain are
  applied, and seeking runs 80 ms ahead of its target. Identical to libopus,
  sample for sample, and passes the RFC 8251 conformance vectors.
  The codec core also supports concealment of lost packets and recovery from
  the redundant copy the next packet carries.
- Tolerant Ogg playback preserves the complete prefix before a damaged page,
  missing page or broken continuation, with its duration and seek range.
- Vorbis, decoded: floor type 1, residue types 0, 1 and 2, channel coupling,
  block switching, and up to eight channels reordered into AudioKodec's channel
  order. Checked against libvorbis sample for sample at every quality level.
  Floor type 0 is parsed and refused, because no encoder has emitted it since
  the format's 2001 beta releases.
- An inverse MDCT and a complex FFT in `commonMain`, checked against the
  transforms' own definitions rather than only against another decoder.
- Ogg comment headers, written, by rebuilding the header pages and renumbering
  the pages after them with the audio packets left untouched.
- Vorbis comments and FLAC picture blocks, read and written, with seek tables,
  cue sheets and application blocks carried across a tag edit untouched.
- The MPEG audio framing layer: frame headers for MPEG-1, 2 and 2.5 in all
  three layers, sync words confirmed against the frames that follow them,
  `Xing`, `Info` and `VBRI` headers, LAME gapless trimming, and exact
  durations for constant and variable bitrate files alike. Layers I, II and
  III all decode, with sample-accurate seeking.
- `kiteaudio-codec-io`, the optional kotlinx-io bridge.

### Build

- The project builds with the Kotlin Toolchain (`./kotlin`) instead of Gradle,
  on Kotlin 2.4.21.

## KiteSynth before the merge

These entries come from the changelog of the KiteSynth repository. They name the
artifact `kitesynth`, which is `kiteaudio-synth` now.

### After 0.2.0

Build:

- The project builds with the Kotlin Toolchain (`./kotlin`) instead of Gradle, on Kotlin 2.4.21.
- Android builds compile against API level 37.2.

### 0.2.0

Breaking changes:

- `SampleHeader` holds its own frames in `data`. `start` and `end` are gone, and loop points count from the first frame of the sample.
- `SoundFont.sampleData` is gone. `SoundFont` gains `info`, the INFO text of the file.
- `Voice.start` no longer takes a sample pool.
- Channel 9 is a drum channel from the start. When the font has no kit, it is silent instead of playing the first preset.
- Controller 121, Reset All Controllers, keeps volume, pan, bank, program and the effect sends, as General MIDI says. `Synth.reset(channel)` still resets everything except program and bank.
- `referenceA4` now tunes every melodic note. A note keeps the table tuning and A4 it started with; pitch bend and RPN tuning still move it.
- Controller values are 7-bit, as MIDI's are. A bank select above 127 no longer reaches bank 128: use `setPercussion` for drums.
- `SoundFont.voicesFor(bank, program, ...)` picks a preset the way a channel does. Bank 128 is drums, and a missing bank tries bank 0 before the first preset.
- A note plays its zone's filter, modulation envelope, LFOs and modulators. A font now sounds as its author programmed it, which is often darker and moves more than in 0.1.
- Volume (controller 7) and expression (controller 11) follow the concave curve of the SoundFont default modulators. A middle value is quieter than before.
- `VoicePlan` has two more constructor parameters, `generators` and `modulators`. Both have defaults.

Added:

- `SoundFontWriter`, which writes a font that reads back unchanged.
- `SoundFont.merged` and `SoundFont.pruned`. The merged font is pruned. Both keep stereo and linked samples together, and a link to a missing sample becomes a mono sample.
- Modulators on every zone.
- Percussion channels with `setPercussion` and `isPercussion`, and bank 128 kits.
- `presetOn`, `bankOn`, `programOn`, `controller`, `bendRangeSemitones`, `channelPressure` and `polyPressure` on `Synth`.
- Pitch bend range, fine tune and coarse tune through RPN data entry.
- The sostenuto pedal, pan from controller 10 for new notes, and channel and key pressure.
- Pitch bend moves notes that are already sounding.
- The volume envelope moves in decibels, as the format means it. The attack rises in amplitude, and the key number scales hold and decay around middle C.
- A resonant low-pass filter on every voice, `LowPassFilter`.
- The modulation envelope and both LFOs, `Lfo`. Vibrato, tremolo and filter sweeps now sound.
- Modulators change the sound. `DefaultModulators` holds the SoundFont 2.04 defaults. A zone's own modulator adds to them, or replaces the default it matches.
- Controllers, channel pressure, key pressure and the pitch wheel move notes that are already sounding, through the modulators that read them.
- Reverb and chorus sends on every voice, and a stereo chorus on `Synth.chorus`. Controllers 91 and 93 set each channel's sends.
- Cubic interpolation, on by default. `Synth.interpolation = Interpolation.Linear` costs less.
- `Units.gainOf`, which turns centibels into a gain from a table.
- SF3 fonts. `KiteSynth.readFont(bytes, keepCompressed = true)` keeps each sample's Ogg Vorbis bytes in `SampleHeader.compressed`. `SoundFont.decoded` turns them into frames with a decoder you pass in, and `SoundFontWriter` writes a font that is still compressed as an SF3 file. KiteSynth still depends on nothing.

Fixed:

- A sample header that claims frames past the data is cut to the frames that exist.
- An SF3 font read without `keepCompressed` is refused with a message instead of playing noise. A `Synth` refuses a font whose samples are still compressed.
- A NaN or an infinity in a voice or in the room's input comes out as silence and no longer stays in the filter or the reverb. A value that overflows inside the room empties it.
- The reverb stops working once its input and its tail both stay under -120 dB, and empties itself. A silent room then costs almost nothing and gives out exact zeros.
- A released note ends once its sound stays under -90 dB for 50 ms. Before, it ran until its envelope alone had fallen 96 dB, often seconds after anyone could hear it.
