<!-- Release: v0.33.19 -->

# Release v0.33.19

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized build is not debuggable and is minified with R8. It updates the existing app in place.

## Changes

- Correct list artwork placement after long autoscroll.
- Improve tag editor keyboard motion, save progress, background completion and verified undo copies.
- Guard the Android 8 test process against a framework accessibility race.
- Correct retained local-function callbacks and split an overlong documentation paragraph.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/635 - List item offsets after long autoscroll.
- https://github.com/anastaci1a/localica/issues/699 - Tag editor keyboard motion and save completion checks.
- https://github.com/anastaci1a/localica/issues/700 - Reversible tag saves, progress and undo verification.
- https://github.com/anastaci1a/localica/issues/730 - Android 8 test-process accessibility crash guard.

## Partial completion

- https://github.com/anastaci1a/localica/issues/733 - Correct four local-function reference sites. The remaining sweep and lint rule stay open.

## Known issues

- Artwork restoration diagnostics remain under investigation: https://github.com/anastaci1a/localica/issues/530.
- Rare subview freezes after a fling and pinch remain under investigation. Use Export diagnostics if encountered: https://github.com/anastaci1a/localica/issues/535.

## Install

1. Download `localica-v0.33.19-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Original schema-6 builds cannot reopen schema-7 queues.
