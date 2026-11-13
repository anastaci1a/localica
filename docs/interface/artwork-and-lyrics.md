# Artwork and lyrics

The player's background is the cover itself, extended by its own colours. Lyrics are a mode of the same screen, not a separate page: artwork fades, lyrics blend into the upper screen, and controls move down. Song changes always animate, and the previous complete picture stays until the next one is ready.

- Two artwork layouts: full-background (default) and floating cover.
- Swiping the cover changes song. It follows the finger and can be turned off.
- Lyrics open already at the current line and follow playback until the user scrolls.
- A tap on the artwork toggles lyrics by default. Tap and hold are configurable.
- Status: Implemented.

## Artwork layouts

| Layout | Look |
| --- | --- |
| Full-background (default) | The cover scales to screen width without side cropping. Its lower third feathers into broad curved colour regions that keep the cover's left and right colours. |
| Floating cover | The cover sits over an artwork-coloured matte gradient with subtle texture, and keeps defined edges. |

A solid frame or border on a cover sets the continuation's tone beyond that edge, easing toward interior colours further out. Frames are detected when artwork is prepared, never per frame. The continuation never fades straight to one average colour.

During expansion and collapse, artwork draws above overlapping title and artist text. At rest it stays below them.

## Song changes

| Trigger | Transition |
| --- | --- |
| Buttons and autoplay | Blur-through by default: soften the outgoing cover, blend near peak blur, resolve the incoming one. A perspective carousel is the alternative. |
| Cover swipe | The covers follow the finger, feathered through their blended colour continuation. |
| Reduced motion | A short fade, without blur or travel. |

- A short peek returns. Enough travel, or a directional flick, commits once on release.
- A right swipe goes to the actual previous visit, whatever the Previous restart preference.
- Re-grabbing a settling carousel keeps its displayed position.
- Every playback visit animates, even between identical covers.
- Up to four visits in each direction are prepared from the real traversal, shuffle and history included.
- A cold start shows only the shared gradient, never a music icon or a white backing.

Album-art swiping is a switch in **Settings → Interface → Player**, on by default.

## Lyrics mode

Both artwork layouts animate into one integrated lyrics state, with no boundary and no Lyrics heading.

- The selected mode sets all target positions throughout a held player movement. Nothing switches at release.
- The lyrics viewport is mounted to the moving player. Both of its edges travel with the player, and the current line keeps its relative position.
- Lyrics fade out early in collapse and fade in late in expansion. Artwork returns continuously above them.
- Album-only extra actions stay hidden throughout lyrics-mode movement.
- Lyrics stay open across songs and crossfade their documents.
- Every lyrics entry point stays available, even without local lyrics.

### Lyrics header

The top-right lyrics bubble widens into a divided pill. Its lyrics icon moves left to select sources. A crossed-out eye on the right hides lyrics. Source selection first separates the pill into two circles, then expands the left circle into its popup. Return restores the circle before rejoining. Only one surface owns the left material and shadow at a time.

A source or track change closes the outgoing menu without applying stale choices.

## Lyrics

- Lyrics open at the current line, centred in the container, with no opening scroll. Following keeps that centre anchor.
- The active line is emphasized. Movement is fluid, and the viewport edges fade, inspired by [Better Lyrics](https://github.com/better-lyrics/better-lyrics).
- Manual scrolling suspends following. A glass directional bubble returns to the current line.
- Tapping a timed line seeks to it. Plain lyrics scroll normally.
- With touch exploration, following starts off.
- Lyrics take taps and swipes throughout the artwork crossfade. Disabled artwork gestures leave no hit target over them.

The empty state appears only after the lookup finds no usable lyrics and no read error. It never shows during loading or failed reads. Available lyrics stay visible when another source fails. Source rules are in [Library](../architecture/library.md#lyric-sources).

### Timing

Timed lyrics interpolate Media3's latency-aware position between samples. They freeze during pause and buffering, and bound stale extrapolation. A saved correction from −2000 to +2000 ms, in 50 ms steps, shifts only the highlighting. Positive means later. It never changes seek targets or audio.

No Bluetooth delay is guessed: [Media3 tracks output latency](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackPositionTracker.java) where the route reports it. [Timestamps can be unavailable for a route](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp)), so inaccurate outputs still need calibration on the device.

## Artwork gestures

| Gesture | Default |
| --- | --- |
| Tap | Toggle lyrics. |
| Hold | Menu: View artwork, Change artwork, Configure tap action. |

Tap and hold can each be mapped to lyrics, menu, viewer, change or nothing. Accessible alternatives remain. Change artwork opens image selection, then the artwork-only [review and write flow](actions-and-editing.md#tag-editor) for the current song only.

## Tests

| Topic | Classes |
| --- | --- |
| Artwork look | `ArtworkWashTest`, `ArtworkWashBorderTest`, `PlayerArtworkLayerTest`, `PlayerArtworkHoldTest` |
| Song changes | `ArtworkSwipeTest`, `ArtworkOccurrenceTransitionTest`, `ArtworkBlendLoadingTest`, `PlayerSkipBackgroundTest`, `PreparedPlayerVisualCacheTest` |
| Lyrics mode | `PlayerLyricsMountTest`, `LyricsViewportMotionTest`, `LyricsHeaderActionsTest`, `GlassOriginPreparationTest` |
| Lyrics | `LyricsInteractionTest`, `LyricsLineAnchorTest`, `MissingLyricsMotionTest`, `LyricsPausedSeekTest` |
