<!-- Release: v0.30.1 -->

# Release v0.30.1

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This release is now an optimized build: not debuggable, minified with R8, with a baseline profile. It still updates the existing app in place. The automated test suites run on the debug variant; the optimized APK was checked by launch and input smoke tests and on a physical device.

## Changes

- Much faster scrolling, startup and library loading, especially in dense galleries, scrollbar drags and long returns to the top or current song.
- Keep loading work, decoding and scan progress off the UI thread, and reuse artwork more efficiently at high gallery densities.
- New setting: **Live glass while scrolling** (off by default). When off, glass over a scrolling list holds its last backdrop until scrolling stops.
- New setting: **Long scroll animation** (Fly-through by default, or Jump, then glide, or Fade) for long returns and follow scrolls. Reduced motion still applies.

## Resolved issues

None. Responsiveness work continues in https://github.com/anastaci1a/localica/issues/406.

## Known issues

- Gapless transitions can still glitch on some files (https://github.com/anastaci1a/localica/issues/28).

## Install

1. Download `localica-v0.30.1-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Original schema-6 builds cannot reopen schema-7 queues.
