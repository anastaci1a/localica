<!-- Release: v0.34.0 -->

# Release v0.34.0

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Share the motion engine across player, Queue, popup, scrollbar, pinch and settings-search interactions.
- Consolidate control layouts, lyrics-header presentation, transport outlines, preference frames and image chooser geometry.
- Keep popup navigation and native-window preparation continuous through closing, reversal and nested handoffs.
- Respect Android animation speed and Remove animations, preserve resting-player navigation, and retain ongoing scrollbar motion during reduced-motion changes.
- Provide the shared UI context to tag editor, jobs and interrupted-edit recovery dialogs.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/721 - Keep Search direct return independent of native keyboard movement.
- https://github.com/anastaci1a/localica/issues/818 - Provide the shared engine context to root-level tag editor and recovery dialogs.
- https://github.com/anastaci1a/localica/issues/667 - Keep utility popup returns behind the mini player and bottom navigation.
- https://github.com/anastaci1a/localica/issues/676 - Preserve player collapse when Back interrupts a closing lyrics drag.
- https://github.com/anastaci1a/localica/issues/679 - Reduce repeated UI lint graph work.
- https://github.com/anastaci1a/localica/issues/693 - Preserve source shadows through canceled popup openings and returns.
- https://github.com/anastaci1a/localica/issues/811 - Retain popup parents until descendant handoff completes.
- https://github.com/anastaci1a/localica/issues/812 - Preserve outgoing popup continuity during host navigation.
- https://github.com/anastaci1a/localica/issues/813 - Verify complete native shadow-field receipts for existing controls.
- https://github.com/anastaci1a/localica/issues/814 - Preserve native preparation, reversal and closing-window retirement.
- https://github.com/anastaci1a/localica/issues/815 - Retain complete popup shadows through reversal and retirement.
- https://github.com/anastaci1a/localica/issues/835 - Respect Android animation duration and Remove animations.
- https://github.com/anastaci1a/localica/issues/836 - Bound popup retirement when its mounted trigger is no longer drawn.
- https://github.com/anastaci1a/localica/issues/837 - Keep a resting player at rest during collection navigation.
- https://github.com/anastaci1a/localica/issues/838 - Preserve ongoing scrollbar motion when reduced motion changes.
- https://github.com/anastaci1a/localica/issues/841 - Check merge commits without the workflow-guard false positive.

## Known issues

- The extended Reduced-Motion audit of popup parent/child handoffs, Search return/reopening and live reduction continues as the first v0.34.1 item.

It is incomplete for v0.34.0; its findings and strict acceptance criteria remain open.

- Native popup IME test flake on API 36: https://github.com/anastaci1a/localica/issues/786.
- Last.fm group-list test flake at large fonts in RTL on API 26: https://github.com/anastaci1a/localica/issues/787. Its fix follows this release.
- API 36 startup test failure with an empty failure and device offline: https://github.com/anastaci1a/localica/issues/824. Complete coverage remains required.
- Native emulator-loss recovery remains unqualified: https://github.com/anastaci1a/localica/issues/738.
- WindowCapture test flakes: https://github.com/anastaci1a/localica/issues/734.
- DocumentsUI and artwork restoration test flakes: https://github.com/anastaci1a/localica/issues/530. These failures are not waived.
- Remaining Last.fm performance work: https://github.com/anastaci1a/localica/issues/780 and https://github.com/anastaci1a/localica/issues/794.
- The 8.3 ms whole-frame performance objective remains unmet and unchanged.

The fresh release waiver covers only the exact filed signatures of #786, #787 and #824. Every other retained release gate remains required.

## Install

1. Download `localica-v0.34.0-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
