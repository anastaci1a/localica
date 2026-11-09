<!-- Release: v0.33.15 -->

# Release v0.33.15

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized build is not debuggable and is minified with R8. It updates the existing app in place.

## Changes

- Player header shadows retain their full bounds while the player fades and minimizes.
- Showing quick search at the top of a browse list reveals its header on the first presented frame.
- Settings search appears only on the root settings page. Search results still open and highlight controls on their destination pages.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/709 - Player header shadow clipping during minimize.
- https://github.com/anastaci1a/localica/issues/695 - Quick-search header reveal at the visual list top.
- https://github.com/anastaci1a/localica/issues/696 - Root-only settings search with preserved deep navigation.

## Known issues

- Artwork restoration diagnostics remain under investigation: https://github.com/anastaci1a/localica/issues/530.
- Rare subview freezes after a fling and pinch remain under investigation. Use Export diagnostics if encountered: https://github.com/anastaci1a/localica/issues/535.

## Install

1. Download `localica-v0.33.15-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
