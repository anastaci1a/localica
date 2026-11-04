<!-- Release: v0.30.8 -->

# Release v0.30.8

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Pinching between view sizes starts immediately, with no delay or jump. It follows your fingers smoothly in both directions, can reverse mid-gesture, and fades items in and out symmetrically.
- During a view-size pinch, earlier items in a list draw above later ones, so overlapping cells layer in reading order.
- The player and Queue choose light or dark controls from the part of the artwork background that is actually behind them.
- The small utility popup no longer shows bright lines along its top and bottom edges, and the playlist menu uses the same glass menu as the rest of the app.
- Fixed a crash on Android 8 when artwork was captured during player and pinch animations.
- In Artist views, Year sorting keeps each album's discs and tracks in ascending order, and the album carousel matches the list.
- Legacy control drawing reuses its bitmaps instead of allocating new ones every frame, which reduces stutter on older devices.
- Updated the Compose UI libraries (BOM 2026.03.01), with fixes for the behaviour changes it required.
- Pinch handoff guards: a normal finish no longer runs the abandon path, and a zero-size cell is not drawn.
- A library refresh during a collection pinch no longer leaves later pinches stuck.
- MP3 tracks play back to back without the short gap on Android 8's built-in MP3 decoder.
- Settings, the scanner and the exit dialog take their colors from the current artwork theme, and dialogs keep the backdrop behind them.
- Large density titles can use two lines, and Small density items are 40 dp tall again.
- Quick Scan is named consistently, and its settings explain how Include subfolders and per-folder exclusions work.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/536 - Smooth, symmetric view-size pinching (retargeting, mirrored and proportional fades, departures).
- https://github.com/anastaci1a/localica/issues/520 - View-size pinch starts immediately instead of after a delay and a jump.
- https://github.com/anastaci1a/localica/issues/522 - Earlier browse items layer above later ones.
- https://github.com/anastaci1a/localica/issues/473 - Player and Queue theme from the artwork actually behind the controls.
- https://github.com/anastaci1a/localica/issues/369 - No bright top and bottom lines on the small utility popup.
- https://github.com/anastaci1a/localica/issues/501 - Android 8 native crash freeing a bitmap (graphics readback overrun).
- https://github.com/anastaci1a/localica/issues/544 - Recurring Android 8 emulator graphics aborts in CI (fixed by #501).
- https://github.com/anastaci1a/localica/issues/471 - Artist album sorting keeps ascending disc and track order.
- https://github.com/anastaci1a/localica/issues/479 - Less repeated bitmap allocation in Legacy control drawing.
- https://github.com/anastaci1a/localica/issues/499 - Compose UI library upgrade and the test frame-clock race on Android 8.
- https://github.com/anastaci1a/localica/issues/543 - CI builds test APKs once and shares them with every device shard.
- https://github.com/anastaci1a/localica/issues/397 - CI reuses verified checks for code-identical release commits.
- https://github.com/anastaci1a/localica/issues/542 - v0.30.6 review follow-ups: pinch handoff completion and zero-size guards (the seek rounding item shipped in v0.30.7).
- https://github.com/anastaci1a/localica/issues/547 - The pinch release anchor test measures the full outline, so a crossing cover clipping the highlight no longer fails it.
- https://github.com/anastaci1a/localica/issues/30 - MP3 boundaries are trimmed correctly with the legacy Android 8 decoder.
- https://github.com/anastaci1a/localica/issues/310 - Dialog and root-screen appearance follows the active artwork theme.
- https://github.com/anastaci1a/localica/issues/309 - Large titles use two lines; Small density restored to 40 dp.

## Known issues

- Gapless transitions between AAC/M4A tracks need a listening check on a device (https://github.com/anastaci1a/localica/issues/28).
- The subview whole-list freeze (case b of https://github.com/anastaci1a/localica/issues/535) awaits device retest.

## Install

1. Download `localica-v0.30.8-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
