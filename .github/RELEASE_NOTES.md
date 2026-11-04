<!-- Release: v0.31.0 -->

# Release v0.31.0

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Item outlines are standardized: corners are concentric with the artwork, the artwork sits fully inside the outline, and every list and gallery uses one shared outline geometry.
- Multi-select uses a new hover-lift model instead of glass bubbles: selected items lift with a soft shadow, and adjacent selected items merge into one outline until a drag starts.
- The multi-select action popup is redesigned as a compact glass pill. It morphs between expanded and collapsed states with a spring, shows the selection count while collapsed or dragging, moves extra actions into More, and wraps cleanly at large font sizes.
- During player transitions, the moving artwork now draws above the title and artist text.
- Play Next now really plays next, including with shuffle on and after going back with Previous. Newer Play Next batches go first, and each batch keeps its order.
- Listening statistics are no longer reset or double-counted when you edit a song's tags while it's playing.
- List view at Smallest density has half the spacing between titles.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/532 - Standardized item outlines: concentric corners, contained artwork, one shared geometry.
- https://github.com/anastaci1a/localica/issues/533 - Hover-lift selection model replaces multi-select glass bubbles.
- https://github.com/anastaci1a/localica/issues/534 - Overhauled multi-select action popup.
- https://github.com/anastaci1a/localica/issues/391 - Transitioning player artwork draws above the title and artist, and the frosted controls keep sampling the cover throughout.
- https://github.com/anastaci1a/localica/issues/196 - Play Next updates the actual playback journey.
- https://github.com/anastaci1a/localica/issues/197 - Listening statistics survive tag edits.
- https://github.com/anastaci1a/localica/issues/548 - Halved title gap in List-Smallest density.
- https://github.com/anastaci1a/localica/issues/550 - Faster CI: full test runs are under 30 minutes.
- https://github.com/anastaci1a/localica/issues/551 - PopupFamilyPresentationTest no longer times out on Android 16 (test fixture; real Back dismissal was verified working).

## Known issues

- Startup responsiveness on large libraries still needs measuring on a physical device (https://github.com/anastaci1a/localica/issues/406).
- Play Next has rare edge cases around in-flight track changes and shuffle cycle boundaries (https://github.com/anastaci1a/localica/issues/549).
- Gapless transitions between AAC/M4A tracks need a listening check on a device (https://github.com/anastaci1a/localica/issues/28).

## Install

1. Download `localica-v0.31.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
