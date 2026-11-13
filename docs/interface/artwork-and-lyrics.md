# Artwork and lyrics

The player's background is the cover itself, extended by its own colours. Lyrics are a mode of the same screen, not a separate page: artwork fades, lyrics blend into the upper screen, and controls move down. Song changes always animate, and the previous complete picture stays until the next one is ready.

- Two artwork layouts: full-background (default) and floating cover.
- Swiping the cover changes song. It follows the finger and can be turned off.
- Lyrics open already at the current line and follow playback until the user scrolls.
- A tap on the artwork toggles lyrics by default. Tap and hold are configurable.
- Status: Implemented.


## Artwork

Artwork and colour continuation form one player background. Prepared cached visuals appear before higher-resolution replacements crossfade. Occurrence-based transitions keep playback identity separate from artwork identity. Skip, swipe and interrupted-load tests specify direction, blend and first-frame continuity.

## Lyrics mode

The artwork and lyrics are complementary presentations within one player. A divided top-right pill owns the lyrics toggle and its actions. Draft options never apply after their source becomes stale. Layer and mount tests specify the transition and continuous input ownership.

## Lyrics

Timed lyrics start centred on the current line. Following maintains that anchor until manual scroll suspends it; a directional bubble returns. Tap seeks to a timed line. Plain lyrics scroll normally; touch exploration starts with following off. Lyrics retain input throughout artwork crossfades.

Empty state requires a completed lookup with no usable lyrics and no read error. Existing lyrics survive failures in another source. [Library](../architecture/library.md#lyric-sources) owns source discovery. Interaction, line-anchor and missing-lyrics tests specify viewport and failure sequences.

### Timing

Highlighting interpolates latency-aware playback position, freezes during pause/buffering and bounds stale extrapolation. A saved correction from −2000 to +2000 ms in 50 ms steps affects highlighting only; positive means later. Seek targets and audio remain unchanged.

[Media3](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackPositionTracker.java) tracks reported output latency. Routes without reliable [timestamps](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp)) still need device calibration; no Bluetooth delay is guessed.

## Artwork gestures

Tap defaults to lyrics; hold opens View artwork, Change artwork and Configure tap action. Each gesture may instead target lyrics, menu, viewer, change or nothing, with accessible alternatives. Change uses the [artwork-only review flow](actions-and-editing.md#tag-editor) for the current song.

## Tests

[ArtworkWashTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkWashTest.kt).

[ArtworkWashBorderTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkWashBorderTest.kt).

[PlayerArtworkLayerTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerArtworkLayerTest.kt).

[PlayerArtworkHoldTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerArtworkHoldTest.kt).

[ArtworkSwipeTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkSwipeTest.kt).

[ArtworkOccurrenceTransitionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkOccurrenceTransitionTest.kt).

[ArtworkBlendLoadingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ArtworkBlendLoadingTest.kt).

[PlayerSkipBackgroundTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerSkipBackgroundTest.kt).

[PreparedPlayerVisualCacheTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PreparedPlayerVisualCacheTest.kt).

[PlayerLyricsMountTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerLyricsMountTest.kt).

[LyricsViewportMotionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LyricsViewportMotionTest.kt).

[LyricsHeaderActionsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LyricsHeaderActionsTest.kt).

[GlassOriginPreparationTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/GlassOriginPreparationTest.kt).

[LyricsInteractionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LyricsInteractionTest.kt).

[LyricsLineAnchorTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LyricsLineAnchorTest.kt).

[MissingLyricsMotionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/MissingLyricsMotionTest.kt).

[LyricsPausedSeekTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/LyricsPausedSeekTest.kt).
