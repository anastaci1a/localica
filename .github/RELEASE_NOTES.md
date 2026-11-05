<!-- Release: v0.33.1 -->

# Release v0.33.1

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Album carousels no longer randomly hide artwork when you select or deselect an album, and carousel albums use the standard gallery gap.
- The scrollbar shows one continuous position in every list, with no jump at the end of the list.
- The album art viewer now has a Save button (to Downloads) in place of the + button.
- Icon packs now live in an internal library: a pack list, instant switching between packs, and a viewer that shows each pack's icons.
- Behind the scenes: flaky tests (search and sort reset, player backdrop hang, large-queue transport) were fixed, and prototype CI can repeat exact test methods.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/574 - Album carousel artwork no longer hides on select/deselect.
- https://github.com/anastaci1a/localica/issues/573 - Album carousel uses the standard gallery gap.
- https://github.com/anastaci1a/localica/issues/582 - Continuous scrollbar position with no jump at the list end.
- https://github.com/anastaci1a/localica/issues/585 - Album art viewer Save (to Downloads) replaces the + button.
- https://github.com/anastaci1a/localica/issues/597 - Icon pack library: pack list, instant switching and a per-pack icon viewer.
- https://github.com/anastaci1a/localica/issues/581 - SearchSortResetTest.globalReset flake fixed (test only).
- https://github.com/anastaci1a/localica/issues/586 - PlayerBackdropTest API 26 shard hang fixed (test only).
- https://github.com/anastaci1a/localica/issues/602 - LargeQueueTransportTest provider-open expectation fixed (test only).

## Known issues

- Crash when holding the artwork in the full-screen player: can't be reproduced yet; logs requested (https://github.com/anastaci1a/localica/issues/575).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).
- Selection outlines don't yet merge with smooth concave joins (https://github.com/anastaci1a/localica/issues/556).

## Install

1. Download `localica-v0.33.1-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
