<!-- Release: v0.33.10 -->

# Release v0.33.10

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Changing the view from the View popup, such as gallery items per row, now transitions like a pinch instead of jumping items into place.
- A song selected in one view stays selected in every other view, so multi-select no longer counts it twice.
- Every list has a scrollbar that fades when idle and is easier to grab. Taps beside it still reach the row.
- Lists end with enough room that the floating buttons no longer cover the last row, including scoped search results.
- The circular seek and revert icons point their arrowheads in the right direction in the icon pack viewer and controls.
- Fix two test flakes: Queue material readiness and the search keyboard wait.
- Rebalance the measured test shard costs.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/627 - Transition View popup view changes like a pinch.
- https://github.com/anastaci1a/localica/issues/651 - Recognise a selected song across views in multi-select.
- https://github.com/anastaci1a/localica/issues/652 - Leave room below the last row for the floating buttons.
- https://github.com/anastaci1a/localica/issues/648 - Add an auto-fading scrollbar with a larger tap target to every list.
- https://github.com/anastaci1a/localica/issues/659 - Point the circular-arrow icon arrowheads the right way.
- https://github.com/anastaci1a/localica/issues/645 - Fix the FrostedSurfaceTest Queue material readiness flake.
- https://github.com/anastaci1a/localica/issues/593 - Fix the ListSearchTest search keyboard timing flake.

## Known issues

- Diagnostics added, not fixed yet: ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.10-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
