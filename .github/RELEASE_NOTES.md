<!-- Release: v0.33.11 -->

# Release v0.33.11

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Optional, read-only Last.fm statistics: enter a Last.fm username in **Settings → Last.fm** to sync play counts matched to your library. Localica never scrobbles or posts anything.
- A song selected in one view stays selected in every other view, while repeated entries in a playlist or queue stay separately selectable.
- Every list has a scrollbar that fades when idle and is easier to grab, and taps beside it still reach the row. Lists end with enough room that the floating buttons no longer cover the last row.
- Imported icon packs now animate the play ↔ pause morph.
- Skip forward and back animate with soft opacity tapers instead of hard clip edges.
- Tapping a tab away from the lens switches immediately, with its haptic, instead of waiting for a hold or drag.
- Pinching between views fades disappearing items across the whole transition, without jumps at recognition or a selected-item size stutter.
- Groundwork for the artist page redesign and the "Longest listened" sort: artist rankings and release data (not visible yet).
- Test and CI reliability: shared Gradle dependency caches (no more download timeouts), the frame-capture settings test, an API 36 System UI emulator crash, API 36 emulators going offline while results are collected, artifact upload retries, a much faster popup view-change test, and rebalanced test shards.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/20 - Add opt-in, read-only Last.fm statistics sync.
- https://github.com/anastaci1a/localica/issues/651 - Recognise a selected song across views while keeping repeated entries distinct.
- https://github.com/anastaci1a/localica/issues/652 - Leave room below the last row for the floating buttons.
- https://github.com/anastaci1a/localica/issues/648 - Add an auto-fading scrollbar with a larger tap target to every list.
- https://github.com/anastaci1a/localica/issues/662 - Animate the play/pause morph with imported pack icons.
- https://github.com/anastaci1a/localica/issues/654 - Use opacity tapers in the skip animations.
- https://github.com/anastaci1a/localica/issues/664 - Switch tabs immediately when tapped away from the lens.
- https://github.com/anastaci1a/localica/issues/559 - Stagger disappearing-item fades across the whole pinch transition.
- https://github.com/anastaci1a/localica/issues/560 - Remove pinch recognition and selected-item size discontinuities.
- https://github.com/anastaci1a/localica/issues/620 - Fix the FrameStatsSettingsTest Start/Stop label flake.
- https://github.com/anastaci1a/localica/issues/673 - Survive the API 36 emulator System UI native abort.
- https://github.com/anastaci1a/localica/issues/675 - Keep collecting test results when an API 36 emulator goes offline.
- https://github.com/anastaci1a/localica/issues/681 - Share complete Gradle dependency caches so CI no longer fails on download timeouts.
- https://github.com/anastaci1a/localica/issues/670 - Retry artifact uploads after connection resets.
- https://github.com/anastaci1a/localica/issues/671 - Cut PopupViewChangeTransitionTest from about 20 to about 4 minutes on API 26.

## Known issues

- Diagnostics added, not fixed yet: ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.11-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
