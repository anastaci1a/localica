<!-- Release: v0.33.16 -->

# Release v0.33.16

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized build is not debuggable and is minified with R8. It updates the existing app in place.

## Changes

- Restore Last.fm settings screens and improve artist photo previews, storage and retries.
- Refine glass action alignment, marquee taper, lyric transitions and loading placeholders.
- Add artist song grouping controls and reduce live None renderer work.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/712 - Reliable artist photo storage, previews and background retries.
- https://github.com/anastaci1a/localica/issues/701 - Vertically centered glass action faces.
- https://github.com/anastaci1a/localica/issues/719 - Queue removal face and stable return coverage.
- https://github.com/anastaci1a/localica/issues/716 - Marquee left taper and cancellation release.
- https://github.com/anastaci1a/localica/issues/564 - Lyric line ink follows the shared theme transition.
- https://github.com/anastaci1a/localica/issues/717 - Current lyric line rests at the viewport center.
- https://github.com/anastaci1a/localica/issues/674 - Stable search return frame sampling.
- https://github.com/anastaci1a/localica/issues/691 - Cheaper live sampling with the None renderer.
- https://github.com/anastaci1a/localica/issues/705 - Sublist loading placeholder contrast.
- https://github.com/anastaci1a/localica/issues/694 - Artist Group by album option and reorder motion.
- https://github.com/anastaci1a/localica/issues/684 - Last.fm settings screens restored (Parts B-D); matching follow-up remains open.

## Known issues

- Artwork restoration diagnostics remain under investigation: https://github.com/anastaci1a/localica/issues/530.
- Rare subview freezes after a fling and pinch remain under investigation. Use Export diagnostics if encountered: https://github.com/anastaci1a/localica/issues/535.

## Install

1. Download `localica-v0.33.16-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
