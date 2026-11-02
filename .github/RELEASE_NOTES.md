<!-- Release: v0.30.3 -->

# Release v0.30.3

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- The shadow strength slider uses a perceptual curve, so each step looks evenly spaced.
- Switching the Library filter (for example from a gallery to Playlists in List) now resets the view and fades the new rows in, instead of moving covers from their old gallery positions.
- Pinching between view sizes works in playlists after the header has scrolled away.
- Covers, the player and its edges no longer jitter by a pixel during pinch-zoom or player expansion, and the thin colored edge near full size is gone.
- The player's light/dark foreground change eases with the lyrics opening animation.
- Skipping tracks no longer flashes a brighter frame in the blurred player background.
- The full-screen player artwork is spaced evenly between the top controls and the title.
- The lyrics panel stays attached to the player while it minimizes from lyrics.
- After a scrollbar drag, artwork loading resumes instead of staying deferred.
- Artwork cells no longer compose a hidden placeholder once the cover is loaded.
- Album art decoding on Android 8–8.1 is hardened against a reported crash. The cause is not yet confirmed, so https://github.com/anastaci1a/localica/issues/501 stays open.
- The release gate now tests the shipped R8 distribution APK, and device tests run in parallel shards.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/502 - Perceptual curve for the shadow strength slider.
- https://github.com/anastaci1a/localica/issues/503 - Playlist view no longer places covers in gallery positions first.
- https://github.com/anastaci1a/localica/issues/504 - Pinch works in playlists after the header scrolls away.
- https://github.com/anastaci1a/localica/issues/505 - Covers no longer jitter while pinch-zooming.
- https://github.com/anastaci1a/localica/issues/506 - Foreground change eases with the lyrics animation.
- https://github.com/anastaci1a/localica/issues/507 - No brighter frame when skipping tracks.
- https://github.com/anastaci1a/localica/issues/508 - Even artwork spacing in the full-screen player.
- https://github.com/anastaci1a/localica/issues/509 - Lyrics panel stays attached while minimizing.
- https://github.com/anastaci1a/localica/issues/510 - No jitter or colored edge near full player size.
- https://github.com/anastaci1a/localica/issues/511 - Release gate tests the R8 distribution APK.
- https://github.com/anastaci1a/localica/issues/512 - Scrollbar drag no longer leaves artwork deferred.
- https://github.com/anastaci1a/localica/issues/513 - Hidden placeholder and unused sizeHint removed.
- https://github.com/anastaci1a/localica/issues/514 - Branch-only scratch workflows removed from main.

## Known issues

- Gapless transitions can still glitch on some files (https://github.com/anastaci1a/localica/issues/28).
- Android 8 album-art crash hardening is in place, but the cause is unconfirmed (https://github.com/anastaci1a/localica/issues/501).

## Install

1. Download `localica-v0.30.3-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Original schema-6 builds cannot reopen schema-7 queues.
