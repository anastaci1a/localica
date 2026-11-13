# Player

The mini-player and the full player are one surface. Tapping or swiping up expands a single outline that carries the glass, artwork, text and transport with it, and every part reverses along the same path. Expansion follows the finger and can be interrupted at any point. Transport controls stay visible throughout.

- The mini-player appears whenever the active queue has a current song, paused included.
- Layout order: artwork, metadata, seek, actions, transport, Queue drawer. There are no volume controls.
- Seeking previews under the finger and commits on release.
- Swipe down or Back collapses and restores the browse state underneath.
- Status: Implemented.


## Mini-player

The body expands the player while transport remains independent. Its second line defaults to Album Artist, falling back to Artist; Artist-first is configurable. Artwork/title share the leading inset. Text uses 13/11 sp.

## Transport icons

Previous, Play/Pause and Next retain presented motion across repeated presses. Pause represents playing or requested playback while buffering; paused skips retain Play. Built-in contours morph. Compatible imported one-shape play/two-shape pause artwork shares that morph; other packs crossfade.

## Expansion

One reversible outline carries glass, artwork, metadata and transport while clipping the Queue. Geometry determines the artwork curve; seek/actions appear late, and the artwork's colour continuation becomes opaque near full size. Device corner radii bound the expanded shape. Layout tests specify intermediate placement.

Song-tap playback expands by default; add-next/append do not. Main tabs leave downward. Handle and down-arrow collapse. Opening thresholds are 240 dp/s or about 22 dp travel; closing player/Queue uses 300 dp/s or 72 dp. Otherwise progress decides. Settlement remains interruptible, with artwork and backing resolved together before detaching.

## Layout

Metadata, seek, actions and lyrics use 32 dp side insets. Floating artwork fits between top controls and title with 8 dp clearance; full-background artwork may overlap and blends into its colour continuation. Lyrics mode lowers controls. [Artwork and lyrics](artwork-and-lyrics.md) owns that presentation.

## Seeking

Seeking is a draft under one input owner. Touch previews immediately; horizontal movement or hold acquires scrub, while initial vertical travel transfers to player motion. Release commits once and retains the visual target through asynchronous completion. X or cancellation discards without pausing. Obsolete media generations cannot commit.

The shared cancel target and [haptic rule](materials-and-controls.md#haptics) apply. Seek tests specify release, takeover, cancellation and handoff frames.

## Titles

Each overflowing line owns its marquee. Expansion pauses it and eases to its start by the shortest path, using soft fades throughout. It restarts only after settlement and delay. Text direction follows content.

## Failures

Loading retains the player and artwork. Genuine playback errors show a dismissible dialog with plain guidance and Retry/Skip. Routine buffering does not replace content with connection text.

## Tests

[PlayerLayoutTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerLayoutTest.kt).

[PlayerExpansionMotionTest](../../app/src/test/java/io/github/anastaci1a/localica/ui/PlayerExpansionMotionTest.kt).

[PlayerTransitionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerTransitionTest.kt).

[PlayerArtworkSpacingTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerArtworkSpacingTest.kt).

[QueuePlayerClipTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/QueuePlayerClipTest.kt).

[PlayerGestureTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerGestureTest.kt).

[SeekReleaseHandoffTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SeekReleaseHandoffTest.kt).

[SeekCancelExitTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SeekCancelExitTest.kt).

[SeekCancelMotionTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/SeekCancelMotionTest.kt).

[PlayerSeekHapticsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/PlayerSeekHapticsTest.kt).

[IconPackPlayPauseMorphTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/IconPackPlayPauseMorphTest.kt).
