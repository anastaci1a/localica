<!-- Release: v0.30.4 -->

# Release v0.30.4

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- The collection header no longer jitters by a pixel while pinching between view sizes.
- Player artwork grows smoothly during expansion, without one-pixel steps.
- Glass popup morphs keep their position and clip edges in step, so popups no longer shimmer at their edges while opening and closing.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/515 - Sub-pixel motion bounds for the collection header pinch.
- https://github.com/anastaci1a/localica/issues/516 - Sub-pixel motion bounds for player artwork growth.
- https://github.com/anastaci1a/localica/issues/517 - Sub-pixel motion bounds for glass popup morphs.

## Known issues

- Gapless transitions can still glitch on some files (https://github.com/anastaci1a/localica/issues/28).
- Android 8 album-art crash hardening is in place, but the cause is unconfirmed (https://github.com/anastaci1a/localica/issues/501).
- After tapping a lyric line while paused, the highlight can stay on the previous line (https://github.com/anastaci1a/localica/issues/518).

## Install

1. Download `localica-v0.30.4-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Original schema-6 builds cannot reopen schema-7 queues.
