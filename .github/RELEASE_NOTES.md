<!-- Release: v0.33.5 -->

# Release v0.33.5

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- None materials average their complete covered area, and nested Queue controls inherit one coherent parent material.
- Held switch lenses keep their sampled backings aligned on older Android versions.
- Queue edit failure tests verify that unavailable player replies remain bounded by the acquisition timeout.
- Emulator readiness recreates resized launchers and requires native navigation clearance before testing.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/624 - Average None materials across their full covered area.
- https://github.com/anastaci1a/localica/issues/625 - Keep Queue controls on one coherent parent material.
- https://github.com/anastaci1a/localica/issues/618 - Make failed queue-restore timeout proofs deterministic.
- https://github.com/anastaci1a/localica/issues/623 - Admit native system bars after emulator resizes.
- https://github.com/anastaci1a/localica/issues/628 - Preserve padded thumbnail coordinates in older Android switch lenses.

## Known issues

- Diagnostics added, not fixed yet: PinchRetargetTest timeouts (https://github.com/anastaci1a/localica/issues/603) and ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.5-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
