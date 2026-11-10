<!-- Release: v0.33.20 -->

# Release v0.33.20

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Saved-queue memory: fixes the out-of-memory crash and heavy stutter with many large saved queues (#774, part 1).
- Correct collection-header sizing when display density changes (#600).
- Improve artist photo display, shared drag behavior and multi-select scrolling.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/742 - Cached artist photos appear on the first frame, with reliable fallback.
- https://github.com/anastaci1a/localica/issues/735 - Queue selector uses shared drag behavior and coherent row placement.
- https://github.com/anastaci1a/localica/issues/736 - Shared drag release haptics.
- https://github.com/anastaci1a/localica/issues/749 - Scroll selected items clear of the multi-select pill.
- https://github.com/anastaci1a/localica/issues/773 - Name published APK assets as pre-release builds.

## Known issues

- WindowCapture test flakes: https://github.com/anastaci1a/localica/issues/734.
- DocumentsUI and artwork restoration test flakes: https://github.com/anastaci1a/localica/issues/530.
- Scroll stutter with a large Last.fm archive: https://github.com/anastaci1a/localica/issues/779 (being fixed).

## Install

1. Download `localica-v0.33.20-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
