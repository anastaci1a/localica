<!-- Release: v0.33.9 -->

# Release v0.33.9

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- With the None glass renderer, every glass surface (switch and slider thumbs, bars, buttons and frosted panels) is filled with the average of the content behind its whole area, and controls inside panels sample the panel's finished material.
- Restart the system keyboard after the floating keyboard test so later tests start clean (follow-up to #638).
- Allow up to five repeats per prototype run on the self-hosted test runner (follow-up to #592).
- Accept green repeated-method prototype runs in the delivery decision.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/624 - Fill None glass surfaces with the average of their full area.
- https://github.com/anastaci1a/localica/issues/657 - Record the same method inventory for repeated-method prototype runs as each iteration does.

## Known issues

- Diagnostics added, not fixed yet: ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.9-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
