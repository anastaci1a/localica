<!-- Release: v0.33.4 -->

# Release v0.33.4

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Selection outlines merge smoothly during hover and drag, with shared soft shadows and lower outline allocation costs.
- A held pinch keeps its highlight aligned with the returning artwork.
- Retracting popups stay within their original host and preserve gesture takeover across navigation.
- CI verifies cached emulator downloads, captures frames after redraw completes, and accepts complete release evidence with class timings.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/613 - Verify cached emulator SDK archives and retry corrupt downloads.
- https://github.com/anastaci1a/localica/issues/612 - Give synthetic scan permission requests fresh tasks.
- https://github.com/anastaci1a/localica/issues/614 - Synchronize native frame captures with completed redraws.
- https://github.com/anastaci1a/localica/issues/617 - Keep held pinch highlights aligned during cancellation.
- https://github.com/anastaci1a/localica/issues/556 - Merge selection outlines with continuous soft shadows and bounded allocation costs.
- https://github.com/anastaci1a/localica/issues/609 - Contain retracting popups within their original host.
- https://github.com/anastaci1a/localica/issues/615 - Stabilize modal gesture admission and drag cleanup tests.
- https://github.com/anastaci1a/localica/issues/619 - Accept complete release evidence with the class-duration profile.
- https://github.com/anastaci1a/localica/issues/621 - Preserve popup material refresh during backdrop pixel updates.
- https://github.com/anastaci1a/localica/issues/622 - Retire playlist editor test sessions before Activity disposal.
- https://github.com/anastaci1a/localica/issues/616 - Match seek-cancel frame fixtures to native system bars and navigation insets.

## Known issues


- Diagnostics added, not fixed yet: PinchRetargetTest timeouts (https://github.com/anastaci1a/localica/issues/603) and ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.4-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
