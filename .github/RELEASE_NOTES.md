<!-- Release: v0.29.0 -->

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

## Changes

- Keep queue order, shuffle transitions and playback navigation consistent across edits and app restarts; improve large-queue persistence and queue-follow scrolling.
- Make library search and sorting more responsive, add finer list densities, and settle pinch and collection gestures more reliably.
- Protect folder scans, playlist imports, artwork permissions and bulk metadata edits from incomplete or interrupted work.
- Refine player controls, lyrics source transitions, and Song Information navigation with readable library paths.
- Improve shared selectors, popup transitions, haptics and reduced-motion behavior; update the in-app previews.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/334 - Restore artwork editing after activity recreation.

## Install

1. Download `localica-v0.29.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
