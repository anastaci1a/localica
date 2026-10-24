Original synthetic 2-second sine tones for codec tests; no third-party audio.
Generated with FFmpeg 7.0.2 using lavfi sine at 440 Hz (MP3), 660 Hz (AAC/M4A),
and 220 Hz (PCM/WAV). MP3 and AAC use 64 kbps. Tagged artist: Localica;
album: Test tones; titles: Morning tone and Quiet interval.

The tagged.* fixtures are synthetic tone.wav copies with test metadata for title,
album, artist, album artist, date, disc, track, and comment. tagged.mp3/m4a/flac
include a generated solid teal 1800x1200 JPEG cover to verify bounded decoding.
The Ogg and Opus variants intentionally have no cover to exercise the fallback.
queue-tone.mp3 is an original 20-second quiet sine tone used to exercise live
queue edits without the track ending during the assertions.
playback-tone.m4a/wav are original 20-second 660/220 Hz tones (AAC 64 kbps
and mono 44.1 kHz PCM), generated with FFmpeg. The transport test uses them
alongside queue-tone.mp3 so pause/seek commands cannot race a two-second end;
it seeks near the end explicitly to verify background completion.

The tone.lrc sidecar and lyrics.opus lyric metadata contain original synthetic
lines for local lyric discovery, source selection, and timing tests.

boundary.flac/mp3/m4a encode an original one-second mono 44.1 kHz 16-bit
sawtooth: sample[i] = ((i % 441) - 220) * 80. Generated using FFmpeg 7.0.2
from a PCM WAV with default format encoders. Repeating it tests exact lossless
samples and encoded delay/padding at natural queue boundaries.
The boundary.m4a sample table declares its complete final 1,024-sample AAC frame
and uses the one-second edit list to trim padding. boundary-edit-list.m4a retains
FFmpeg's original shortened final sample (68 samples), reproducing issue #28:
Uncorrected Media3 1.10.1 leaves 956 padding samples at each such track boundary.
A PCM tail in the boundary test forces the last encoded transition to be checked.

boundary-silent-tail.m4a is an additional original one-second mono 44.1 kHz
16-bit sawtooth with source samples [33075, 44100) set to zero. FFmpeg
5.1.9-0+deb12u1 encoded the PCM WAV using `-map_metadata -1 -c:a aac -b:a 64k`.
Its edit list declares 1000/1000 seconds from media time 1024; its final sample
has duration 68. This checks that metadata trimming retains a quarter-second
of intentional trailing silence. SHA-256:
fae940f7b43d00c0cbc37f0ad96fce0f171074cf25608ee1e457a8539417cbd6

The original boundary.m4a and boundary-edit-list.m4a have identical mdat payloads
(SHA-256 69f1fd8e793f50b6ebb28b67ad30503bff735cd1536bb4899b022cbb54161351).
Both edit lists select exactly 44100 audible samples after 1024 priming samples.
The 45 AAC-LC access units decode to 46080 samples before trimming; 956 end
samples lie outside the edit. This expectation comes from source/timing metadata,
not the current decoder's output. Periodic tones cannot identify every whole-period
shift. Lossy checks use unshifted source windows plus exact PCM-tail placement.

boundary-short-config.m4a derives from boundary-edit-list.m4a. Its esds
DecoderSpecificInfo is reduced from 120856e500 to the two-byte AAC-LC
configuration 1208. Descriptor lengths and containing box sizes decrease by
three bytes; timing and mdat are unchanged. The absent sync extension leaves
SBR presence ambiguous. Its unchanged synthetic payload is AAC-LC, not an
in-band SBR reproduction. The extraction test requires native padding to remain
unchanged because safe correction cannot be established from this configuration.
SHA-256: a8729b2055b0377ed3a2c2a072306dfc64c01f86fb4a005343ed340f8d6d24dc

boundary-streamable.m4a derives from boundary.m4a by moving moov immediately
after ftyp and increasing each stco chunk offset by the moov box size. Its
mdat, codec configuration and timing are unchanged. Placing metadata before
audio lets the native extractor finish through one sequential unknown-length
DataSource open. The regression compares native and application extraction,
including a source that rejects reopening.
SHA-256: e4ff939fbb6abc189a0b1453e8982919ad3a0134fe5a738528592daf1d80d9a2

boundary-rounded-edit.m4a and boundary-rounded-short-config.m4a derive only
from the original synthetic boundary fixtures. All 45 access units declare
1024 media ticks. The media header declares 46080 ticks at 44100 ticks/second.
The sole edit starts at tick 1600 and lasts 1009 movie ticks at 1000 ticks/second;
movie and track headers use that same rounded duration. Its end exceeds the
media extent by less than half a movie-clock tick, but more than Media3's
native two-media-tick tolerance. The bounded media edit contains 44480 ticks.
The mdat payload is unchanged; the second fixture retains the ambiguous
short AAC-LC ASC. It does not reproduce in-band SBR. Decoder-rate binding is
checked separately and must never infer SBR absence from this configuration.
These fixtures contain original generated audio, not private user recordings.
SHA-256:
boundary-rounded-edit.m4a:
b7a8437a379559dfb0483b140cb5c6cdf5a10e4e0789b6e9720715f8cad1b564
boundary-rounded-short-config.m4a:
63a07d27a316c3bd71d03fc3c34b8ac7eed38c2583a1ae7ecf60f67d6df2fd19

boundary-rounded-streamable.m4a moves the rounded short-configuration fixture's
moov immediately after ftyp and increases each stco offset by the moov size.
Its encoded media and edit are unchanged. It exercises post-moov mdat validation
and proves that reading the following free/mdat headers adds no source reopen.
SHA-256: 8ebc21c95d1e9b9d190a61c82ef9c16309b6d295d8858edfb6ea381cb4019d73
