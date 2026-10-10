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

The tone.lrc sidecar and lyrics.opus lyric metadata contain original synthetic
lines for local lyric discovery, source selection, and timing tests.
