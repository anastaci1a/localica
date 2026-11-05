<!-- Release: v0.33.2 -->

# Release v0.33.2

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Holding the album artwork in the full-screen player no longer force-closes the app.
- Row titles and subtitles appear character by character as you scroll, the loading skeleton shows one bar per actual line of text, and the ⋯ button on list rows fades in with the artwork and stays hidden while it loads.
- Going Back in a subview eases the list continuously to the top, and the up-arrow returns to the correct place. The Queue drawer remounts at the top or bottom end, and its jump arrow points the way the list scrolls.
- The (X) cancel target for seeking and sliders no longer vanishes for a frame: it flies down on a normal release and fades out when you release over the X.
- Song and artwork changes in the Queue and player put the surface palette tints on the same clock as the artwork, and the first-open backdrop is graded.
- Shadows no longer pop at the start and end of animations on buttons, popups and selections.
- In mutable lists, the artwork and text no longer vanish for a frame when a drag starts or ends.
- Behind the scenes: flaky tests were fixed (seek glass readiness, queue frame capture) and local tooling deadlines tolerate a loaded host. Added diagnostics for the Pinch retarget (#603) and artwork restoration (#530) flakes.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/575 - Holding the album artwork in the full-screen player no longer crashes.
- https://github.com/anastaci1a/localica/issues/588 - List-row ⋯ button fades in with the artwork, hidden while loading.
- https://github.com/anastaci1a/localica/issues/589 - Row titles and subtitles appear character by character on scroll.
- https://github.com/anastaci1a/localica/issues/590 - Text loading skeleton: one placeholder bar per text line.
- https://github.com/anastaci1a/localica/issues/577 - Subview Back ease to the top and correct up-arrow home position.
- https://github.com/anastaci1a/localica/issues/579 - Queue auto-remount at the ends, and a jump arrow that matches the scroll.
- https://github.com/anastaci1a/localica/issues/584 - Seek and slider (X) cancel target: no one-frame vanish, fly down on release, fade out on X release.
- https://github.com/anastaci1a/localica/issues/605 - SeekCancelGlassTest readiness flake fixed (test only).
- https://github.com/anastaci1a/localica/issues/599 - Queue and player song change: palette tints on the shared artwork clock, first-open backdrop graded.
- https://github.com/anastaci1a/localica/issues/604 - QueueSongTransitionTest frame capture timeout fixed (test only).
- https://github.com/anastaci1a/localica/issues/587 - Shadow continuity at animation starts and ends.
- https://github.com/anastaci1a/localica/issues/580 - Mutable-list drag: artwork and text no longer vanish for a frame at drag start and end.
- https://github.com/anastaci1a/localica/issues/601 - Live-evidence tooling deadlines tolerate shared-host load (local tooling).

## Known issues

- Diagnostics added, not fixed yet: PinchRetargetTest timeouts (https://github.com/anastaci1a/localica/issues/603) and ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).
- Selection outlines don't yet merge with smooth concave joins (https://github.com/anastaci1a/localica/issues/556).

## Install

1. Download `localica-v0.33.2-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
