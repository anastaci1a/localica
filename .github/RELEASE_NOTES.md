<!-- Release: v0.30.2 -->

# Release v0.30.2

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This is an optimized build (not debuggable, minified with R8, with a baseline profile). It updates the existing app in place.

## Changes

- Fix the Now Playing queue showing the previous queue's songs after a play action creates a new queue while the queue drawer is closed.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/500 - Now Playing queue reopens onto the newly created queue.

## Known issues

- Gapless transitions can still glitch on some files (https://github.com/anastaci1a/localica/issues/28).

## Install

1. Download `localica-v0.30.2-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Original schema-6 builds cannot reopen schema-7 queues.
