<!-- Release: v0.33.0 -->

# Release v0.33.0

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Dragging several selected items now shows a live, frosted stack that follows your finger, with the now-playing artwork fading as items are picked up. Autoscroll during a drag keeps the frosted view live.
- Skip forward and Skip back have a new animation, and the transport and tab icons are refreshed. The song menu icons are refreshed too.
- Icons are now swappable: an icon registry and importable icon packs are in place (see docs/icon-packs.md).
- Tapping a Library filter bubble, or changing the full-library search text, scrolls the list back to the top.
- The player backdrop is graded for contrast with the chosen text and icon colour, with gentler saturation and brightness independent of saturation.
- In the Queue drawer, song and artwork changes ease on one shared clock, so the artwork, colours and text change together.
- Dragging the playback bar over the cancel (X) target no longer force-closes the app.
- Pinching in album, playlist and artist views no longer freezes the list when the destination size has no collection header.
- Behind the scenes: several flaky tests (search keyboard motion, Library filter, shuffle mode) were fixed, and the build and test pipeline is faster, with bounded shard times and repeatable test proofs.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/557 - Multi-select drag stack: live recursive frosted panes, visible stacking, now-playing fade.
- https://github.com/anastaci1a/localica/issues/571 - Transport and tab icons refreshed; new skip-forward/back animation.
- https://github.com/anastaci1a/localica/issues/572 - Icon registry and importable icon packs.
- https://github.com/anastaci1a/localica/issues/570 - Song menu action icons refreshed.
- https://github.com/anastaci1a/localica/issues/568 - Library filter bubbles and full-library search reset the list to the top.
- https://github.com/anastaci1a/localica/issues/576 - Player backdrop grading for contrast with the chosen ink.
- https://github.com/anastaci1a/localica/issues/578 - Queue drawer song and artwork changes transition smoothly on one clock.
- https://github.com/anastaci1a/localica/issues/583 - Seek drag over the cancel target no longer crashes.
- https://github.com/anastaci1a/localica/issues/535 - Subview pinch without a reachable collection header no longer freezes (partial, see Known issues).
- https://github.com/anastaci1a/localica/issues/545 - SearchImeMotionTest window-focus flake fixed (test only).
- https://github.com/anastaci1a/localica/issues/565 - LibraryFilterTest readiness flake fixed (test only).
- https://github.com/anastaci1a/localica/issues/566 - ShuffleModePlaybackTest cursor-save ordering flake fixed (test only).
- https://github.com/anastaci1a/localica/issues/591 - CI trim: parallel test and delivery builds, re-measured shards, bounded shard times.

## Known issues

- Crash when holding the artwork in the full-screen player: can't be reproduced yet; logs requested (https://github.com/anastaci1a/localica/issues/575).
- The #535 fix covers pinching without a reachable collection header; other causes of the whole-list freeze after a fling and pinch in subviews may remain. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).
- Selection outlines don't yet merge with smooth concave joins (https://github.com/anastaci1a/localica/issues/556).

## Install

1. Download `localica-v0.33.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
