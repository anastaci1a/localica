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
Media3 currently leaves 956 padding samples at each such track boundary.
A PCM tail in the boundary test forces the last encoded transition to be checked.
