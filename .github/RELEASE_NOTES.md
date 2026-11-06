<!-- Release: v0.33.3 -->

# Release v0.33.3

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Glass has two render modes, Normal and None (the Legacy mode is removed). Below Android 13, glass is flat instead of blurred.
- Settings → frame-stats export writes a small file of recent frame timings, for performance reports.
- Selection outlines now merge and unmerge with smooth, rounded concave joins, and their frost and shadow follow the same outline, including during drags.
- Pinching between view sizes is more continuous at the moment the pinch is recognised, and the selected-item size no longer stutters.
- Behind the scenes: flaky tests (search keyboard motion, switch tap optics, settings link morph, snapshot observer during pinch) were fixed, and the emulator readiness and SDK cache in CI were improved.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/560 - Pinch: continuous at recognition, no selected-item size stutter.
- https://github.com/anastaci1a/localica/issues/600 - UI performance consolidation: glass render modes (part 1) and the measurement harness with frame-stats export (part 2).
- https://github.com/anastaci1a/localica/issues/606 - SwitchTapOpticsTest readiness race fixed (test only).
- https://github.com/anastaci1a/localica/issues/611 - SettingsLinkMorphTest frame-starvation flake fixed (test only).
- https://github.com/anastaci1a/localica/issues/598 - CI emulator readiness poll and SDK image cache.
- https://github.com/anastaci1a/localica/issues/607 - CI: API 26 emulator loss during heavy frame-capture tests mitigated.
- https://github.com/anastaci1a/localica/issues/545 - SearchImeMotionTest window-focus flake fixed (test only).
- https://github.com/anastaci1a/localica/issues/610 - SnapshotStateObserver multithreaded access during pinch fixed.
- https://github.com/anastaci1a/localica/issues/556 - Smooth, continuous concave merging and unmerging of selection outlines.

## Known issues

- Diagnostics added, not fixed yet: PinchRetargetTest timeouts (https://github.com/anastaci1a/localica/issues/603) and ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.3-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
