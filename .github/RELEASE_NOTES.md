<!-- Release: v0.33.6 -->

# Release v0.33.6

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Restore lyrics scrolling, tapping and follow controls throughout artwork transitions.
- Move icon packs under Interface while preserving saved settings navigation.
- Support SVG icon packs with fitted vector artwork.
- Show dedicated Repeat One glyphs and preserve transport icon transitions.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/632 - Restore native lyrics input across transitions and reversal.
- https://github.com/anastaci1a/localica/issues/629 - Place icon packs under Interface and migrate saved settings paths.
- https://github.com/anastaci1a/localica/issues/630 - Load and render SVG icon packs.
- https://github.com/anastaci1a/localica/issues/631 - Use dedicated Repeat One glyphs.
- https://github.com/anastaci1a/localica/issues/636 - Size the prototype CI build heap for concurrent Kotlin compiles.
- https://github.com/anastaci1a/localica/issues/637 - Reset the API 36 test launcher only after an idle display to avoid emulator kernel panics.

## Known issues

- Diagnostics added, not fixed yet: PinchRetargetTest timeouts (https://github.com/anastaci1a/localica/issues/603) and ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.6-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
