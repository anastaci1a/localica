<!-- Release: v0.9.0 -->

Development APK for Android 8 or newer. Signed with the project development key; uses a separate `.debug` application ID. Production distribution signing is not configured.

## Changes

- Rounded reference-based layouts, continuous player gestures, tab swiping, and restored search/list positions
- Independent queue browsing and ordered multi-song dragging, with a floating cancel target and selection outlines
- Richer metadata editing and sorting, customizable player actions, and next-track preloading
- Known limitations: padding tails remain for some AAC files (https://github.com/anastaci1a/localica/issues/28) and the legacy Android 8 MP3 decoder (https://github.com/anastaci1a/localica/issues/30)

## Resolved issues

- https://github.com/anastaci1a/localica/issues/24 - Reference layouts, navigation, and gestures
- https://github.com/anastaci1a/localica/issues/25 - Next-track preloading and audio-boundary checks
- https://github.com/anastaci1a/localica/issues/26 - Extended metadata and collection organization
- https://github.com/anastaci1a/localica/issues/27 - Independent queue browsing and recent-queue restoration
- https://github.com/anastaci1a/localica/issues/29 - Ordered drag stacks, cancellation, and selection outlines

## Install

1. Download `localica-v0.9.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. Open Localica and choose your music folder in Settings.
