<!-- Release: v0.30.0 -->

# Release v0.30.0

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

## Changes

- Add optional Quick Scan for newly discovered music and refine loading, playlist presentation and collection controls.
- Improve queue reuse, shuffle transitions and gesture ownership; accept seeks before the next track’s duration is available.
- Refine artwork viewing, player and lyrics transitions, Repeat dialogs and shared glass controls.
- Consolidate library, image and draw preparation, strengthen native evidence checks, and refresh project documentation.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/171 - Popup disappearance, return shadows and rebound.
- https://github.com/anastaci1a/localica/issues/213 - Incremental pending metadata-refresh bookkeeping.
- https://github.com/anastaci1a/localica/issues/222 - Glass research and documentation work; no Apple-parity claim.
- https://github.com/anastaci1a/localica/issues/269 - Independent opacity for grouped glass shadows.
- https://github.com/anastaci1a/localica/issues/286 - List scroll ownership over container dragging.
- https://github.com/anastaci1a/localica/issues/295 - Canonical queue traversal after disabling shuffle.
- https://github.com/anastaci1a/localica/issues/365 - Reuse equivalent ordered playback queues.
- https://github.com/anastaci1a/localica/issues/366 - Selector dragging from non-scrollable options.
- https://github.com/anastaci1a/localica/issues/368 - Retain seek position through native acknowledgement.
- https://github.com/anastaci1a/localica/issues/376 - Reject stale artwork swipe releases.
- https://github.com/anastaci1a/localica/issues/378 - Open Sort/View on the tapped collection tab.
- https://github.com/anastaci1a/localica/issues/386 - Stable Repeat popup sizing and touch targets.
- https://github.com/anastaci1a/localica/issues/387 - Full-height artwork viewing and kinetic panning.
- https://github.com/anastaci1a/localica/issues/389 - Unified Song Information sections and navigation.
- https://github.com/anastaci1a/localica/issues/392 - Remove the playlist-name search icon.
- https://github.com/anastaci1a/localica/issues/393 - Retain multi-select drags while contact remains down.
- https://github.com/anastaci1a/localica/issues/402 - Intrinsic rounded Queue drawer corners.
- https://github.com/anastaci1a/localica/issues/407 - Revoke background gestures when popups open.
- https://github.com/anastaci1a/localica/issues/409 - Reconciled project contracts and documentation.

- https://github.com/anastaci1a/localica/issues/476 - Retain native diagnostic output.
- https://github.com/anastaci1a/localica/issues/477 - Maintain pinned evidence-tooling contracts.
- https://github.com/anastaci1a/localica/issues/478 - Reuse retained pinch-view indexes.
- https://github.com/anastaci1a/localica/issues/480 - Narrow popup composition observation.
- https://github.com/anastaci1a/localica/issues/481 - Share bounded artwork preview decoding.
- https://github.com/anastaci1a/localica/issues/482 - Require explicit menu icons.
- https://github.com/anastaci1a/localica/issues/483 - Keep selector test helpers outside production.
- https://github.com/anastaci1a/localica/issues/484 - Handle invalid navigation decoding explicitly.
- https://github.com/anastaci1a/localica/issues/485 - Align architecture documentation with current contracts.
- https://github.com/anastaci1a/localica/issues/486 - Reuse glass draw preparation.
- https://github.com/anastaci1a/localica/issues/487 - Reuse compatible Legacy image preparation.
- https://github.com/anastaci1a/localica/issues/489 - Share focused workflow setup.
- https://github.com/anastaci1a/localica/issues/490 - Centralize Window capture ownership.

## Install

1. Download `localica-v0.30.0-debug.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted. It updates the existing development app.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Finish tag edits, recovery and pending metadata refreshes before downgrading. Original schema-6 builds cannot reopen schema-7 queues.
