<!-- Release: v0.33.18 -->

# Release v0.33.18

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized build is not debuggable and is minified with R8. It updates the existing app in place.

## Changes

- Glass (None renderer): offscreen passes per frame cut from ~117 to ~31; Songs scroll frame time about 36% faster, player animation about 12% faster on the A35. Fixes a rendering crash risk.
- Correct lyrics return-arrow bounds, strengthen weak haptics, centre icons optically and restore full tab-slide travel.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/715 - Lyrics return arrow hides at wrapped first and last line bounds.
- https://github.com/anastaci1a/localica/issues/727 - Stronger weak haptic using VIRTUAL_KEY.
- https://github.com/anastaci1a/localica/issues/724 - Optical icon centring, including imported icon packs.
- https://github.com/anastaci1a/localica/issues/728 - Full slide travel after an overflowing tab bar becomes four tabs.

## Partial completion

- https://github.com/anastaci1a/localica/issues/691 - None rendering reductions and crash prevention. Further performance work remains open.

## Known issues

- Artwork restoration diagnostics remain under investigation: https://github.com/anastaci1a/localica/issues/530.
- Rare subview freezes after a fling and pinch remain under investigation. Use Export diagnostics if encountered: https://github.com/anastaci1a/localica/issues/535.

## Install

1. Download `localica-v0.33.18-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Original schema-6 builds cannot reopen schema-7 queues.
