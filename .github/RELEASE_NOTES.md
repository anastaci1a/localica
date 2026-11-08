<!-- Release: v0.33.12 -->

# Release v0.33.12

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- New "Longest listened" sort beside Most played: it orders by qualified plays weighted by song length, everywhere the app sorts.
- Artist photos come from Deezer, with a Wikimedia fallback and album art as the last resort. A shared **Online features** setting controls every online lookup.
- Header glass no longer shows a stale fill after the content behind it changes.
- The restored artwork chooser keeps its footer actions on short screens.
- Faster, steadier device tests: shorter slow-test runs, the search keyboard settle, and qualified assumption skips from test results.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/666 - Add the Longest listened sort across the app.
- https://github.com/anastaci1a/localica/issues/672 - Fetch artist photos from Deezer with a Wikimedia fallback, behind the shared Online features consent.
- https://github.com/anastaci1a/localica/issues/680 - Invalidate stale header glass fills for every queued backdrop change.
- https://github.com/anastaci1a/localica/issues/660 - Keep the restored artwork chooser's bounds and actions on API 26.
- https://github.com/anastaci1a/localica/issues/674 - Fix the SearchImeMotionTest native keyboard settle flake.
- https://github.com/anastaci1a/localica/issues/669 - Take observed assumption reasons from test results.
- https://github.com/anastaci1a/localica/issues/678 - Trim slow device tests.

## Known issues

- Diagnostics added, not fixed yet: ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.12-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
