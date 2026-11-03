<!-- Release: v0.30.5 -->

# Release v0.30.5

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Loading artwork placeholders are tinted with each cover's average colour. On first launch after updating, colours are filled in from the background, visible items first. Text loading bars stay gray.
- Artwork fades in directly over the loading shimmer. The brief music-note frame before the art appeared is gone.
- A row's title and subtitle fade in together with its artwork.
- Covers keep their exact size while pinching between gallery sizes. This fixes the remaining size jitter, most visible on the top-left cover going from 4 to 5 per row.
- The current lyric line and the no-lyrics icon stay centred in the visible lyrics area while the player minimizes and while lyrics open and close.
- Tapping a lyric line while paused now always moves the highlight to that line.
- Playback starts with a little audio already buffered, which avoids rare stutters right at the start on slower devices.
- The last playback position is saved reliably when playback is stopped normally.
- Queue actions report failure, rather than silently doing nothing, if queues couldn't be restored at startup.
- The legacy "Scanner configuration" settings path opens the right page instead of a blank one.
- Search no longer gets stuck opening if closing it was refused mid-animation.
- Test and CI reliability: system crash/ANR dialogs no longer interfere with device tests, and a cache test timing issue is fixed.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/492 - Legacy scanner settings path no longer shows a blank page.
- https://github.com/anastaci1a/localica/issues/494 - Search opening no longer hangs when dismissal is refused.
- https://github.com/anastaci1a/localica/issues/495 - Queue commands are rejected cleanly after a failed restore.
- https://github.com/anastaci1a/localica/issues/496 - The final playback position is persisted on controlled shutdown.
- https://github.com/anastaci1a/localica/issues/498 - Audio output is pre-filled before playback starts.
- https://github.com/anastaci1a/localica/issues/505 - Covers no longer jitter while pinch-zooming between view sizes.
- https://github.com/anastaci1a/localica/issues/518 - Paused lyric taps update the highlight.
- https://github.com/anastaci1a/localica/issues/519 - Lyrics stay centred on the moving lyrics area.
- https://github.com/anastaci1a/localica/issues/524 - Artwork fades in from the loading shimmer.
- https://github.com/anastaci1a/localica/issues/525 - Loading placeholders are tinted with the artwork's average colour.
- https://github.com/anastaci1a/localica/issues/526 - Item text fades in with its artwork.
- https://github.com/anastaci1a/localica/issues/528 - Cache sizing test timing fixed.
- https://github.com/anastaci1a/localica/issues/529 - CI suppresses system crash/ANR dialogs.

## Known issues

- Gapless transitions can still glitch on some files (https://github.com/anastaci1a/localica/issues/28).
- Android 8 album-art crash hardening is in place, but the cause is unconfirmed (https://github.com/anastaci1a/localica/issues/501).
- Pinching quickly in album, playlist and artist views can freeze the header or list (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.30.5-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app. The first launch after updating also adds a small database table (schema 7) for artwork colours.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
