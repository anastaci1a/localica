<!-- Release: v0.30.6 -->

# Release v0.30.6

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Covers keep their exact size while pinching between gallery sizes, fixing the remaining size jitter (most visible on the top-left cover going from 4 to 5 per row).
- The gap between gallery item text and the cover in the row below is halved.
- Pinching quickly in album, playlist and artist views no longer leaves the header frozen and pinching disabled.
- Dragging the seek bar keeps working when the track's duration or connection state updates mid-drag.
- Player artwork no longer freezes half-blurred when a new track arrives while artwork is loading.
- Popup shadows stay continuous through repeated and interrupted opening and closing.
- The shadow strength slider is finer at the low end; its labels still step by 5%.
- Faster, better-balanced device test shards, removal of 23 inactive CI workflows, and focused CI runs that accept a single-job rerun.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/505 - Covers no longer jitter while pinch-zooming between view sizes.
- https://github.com/anastaci1a/localica/issues/521 - Halved gap between gallery text and the next row's artwork.
- https://github.com/anastaci1a/localica/issues/535 - Subview pinch no longer freezes the header (the header case; the list-freeze case awaits device retest).
- https://github.com/anastaci1a/localica/issues/493 - An active scrub survives duration, connection and problem updates.
- https://github.com/anastaci1a/localica/issues/491 - The artwork crossfade completes when a new target arrives during loading.
- https://github.com/anastaci1a/localica/issues/465 - Popup shadows stay stable through repeated opening and closing.
- https://github.com/anastaci1a/localica/issues/540 - A stronger low-end curve for shadow strength.
- https://github.com/anastaci1a/localica/issues/394 - Balanced device test shards.
- https://github.com/anastaci1a/localica/issues/497 - Inactive CI workflows removed.
- https://github.com/anastaci1a/localica/issues/541 - Focused CI runs accept a matching earlier-attempt build after a rerun.

## Known issues

- Gapless transitions can still glitch on some files (https://github.com/anastaci1a/localica/issues/28).
- Android 8 album-art crash hardening is in place, but the cause is unconfirmed (https://github.com/anastaci1a/localica/issues/501).

## Install

1. Download `localica-v0.30.6-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Builds before v0.30.5 cannot open the schema-7 library database, and original schema-6 builds cannot reopen schema-7 queues.
