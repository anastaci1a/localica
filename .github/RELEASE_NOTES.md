<!-- Release: v0.33.17 -->

# Release v0.33.17

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized build is not debuggable and is minified with R8. It updates the existing app in place.

## Changes

- Refine artwork edge tones, None controls, tab haptics and collection loading.
- Add library data export and import for transferring saved song dates and metadata.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/711 - Artwork backdrop edge weighting.
- https://github.com/anastaci1a/localica/issues/722 - Stable tag editor navigation coverage.
- https://github.com/anastaci1a/localica/issues/664 - Consistent tab release haptics, with silent off-lens overflow scrolling.
- https://github.com/anastaci1a/localica/issues/725 - Collection loading placeholders and reveal motion.
- https://github.com/anastaci1a/localica/issues/720 - Library data export and import.

## Partial completion

- https://github.com/anastaci1a/localica/issues/691 - Plan A ships white, non-expanding None controls. Performance work remains open.

## Known issues

- Artwork restoration diagnostics remain under investigation: https://github.com/anastaci1a/localica/issues/530.
- Rare subview freezes after a fling and pinch remain under investigation. Use Export diagnostics if encountered: https://github.com/anastaci1a/localica/issues/535.

## Install

1. Download `localica-v0.33.17-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
