<!-- Release: v0.29.0 -->

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

## Changes

- Improve queue order, shuffle transitions, large-queue persistence and queue-follow scrolling (#281, #286, #295).
- Refine library search, sorted artwork, per-type details, list densities and pinch gestures (#283, #294, #306, #308, #309).
- Protect incomplete folder scans, large playlist imports and pending metadata refreshes (#210, #211, #213).
- Refine player controls, lyrics source transitions, Android Back behavior and Song Information navigation (#205, #285, #290).
- Improve selector geometry, popup transitions and reduced-motion behavior (#209, #259, #282); refresh the README previews.

## Resolved issues

- None closed specifically for v0.29.0. The linked issues above remain open for their remaining acceptance work.

## Install

1. Download `localica-v0.29.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Before installing an older build, finish pending tag edits and recovery. Original schema 6 builds cannot reopen schema 7 queues.
