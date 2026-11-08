<!-- Release: v0.33.13 -->

# Release v0.33.13

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Reworked Settings: clearer menu order, a global search that opens the matching page and eases to the option, and a distinct icon on every row, aligned the same in one-line and multi-line rows.
- Downloading items uses the download icon instead of a plain arrow.
- With the None glass renderer, the taller Navigation settings row matches the other rows.
- Pinch view changes keep a steady frame floor while easing, so subview headers settle right after release (follow-up to #560).
- Test and CI reliability: API 26 test packages are fully compiled ahead of time, repeated prototype runs reuse the exact prebuilt APKs, and test inventories understand maximum SDK gates.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/641 - Rework settings order and add global settings search.
- https://github.com/anastaci1a/localica/issues/655 - Align settings row icons and chevrons in one-line and multi-line rows.
- https://github.com/anastaci1a/localica/issues/649 - Use the download icon for downloading items.
- https://github.com/anastaci1a/localica/issues/668 - Match the two-line Navigation row's None material to the other rows.
- https://github.com/anastaci1a/localica/issues/677 - Compile API 26 test packages ahead of time so performance budgets hold on CI.
- https://github.com/anastaci1a/localica/issues/683 - Validate repeated multi-class prototype runs against the exact prebuilt APKs.

## Known issues

- Diagnostics added, not fixed yet: ArtworkEditRestorationTest timeouts (https://github.com/anastaci1a/localica/issues/530).
- The whole-list freeze after a fling and pinch in subviews may still occur in rare cases. If it happens, use Export diagnostics (https://github.com/anastaci1a/localica/issues/535).

## Install

1. Download `localica-v0.33.13-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
