<!-- Release: v0.33.21 -->

# Release v0.33.21

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Add flat None rendering while preserving the reviewed Normal and Single-Tone appearance.
- Remove large Last.fm opening stalls and reuse matching results during scrolling.
- Enforce operator commit identity, the five-workflow policy and reliable CI dependency preparation.
- Ship #738 emulator-loss diagnostics and recovery gating through #752; native recovery qualification remains incomplete.

## Resolved issues

- https://github.com/anastaci1a/localica/issues/793 - Remove the stale test inventory entry blocking tooling contracts.
- https://github.com/anastaci1a/localica/issues/789 - Enforce operator commit identity and reject attribution trailers.
- https://github.com/anastaci1a/localica/issues/791 - Clean repository history and enforce the five-workflow inventory.
- https://github.com/anastaci1a/localica/issues/752 - Prepare instrumentation dependencies before starting offline device checks.
- https://github.com/anastaci1a/localica/issues/740 - Add flat None rendering with settled pixel regression coverage.
- https://github.com/anastaci1a/localica/issues/775 - Reject retired popup parents across renderer changes.
- https://github.com/anastaci1a/localica/issues/776 - Preserve flat drag surfaces and shadows across redraws.
- https://github.com/anastaci1a/localica/issues/777 - Use current renderer modes consistently in settings and UI specifications.
- https://github.com/anastaci1a/localica/issues/753 - Remove large synchronous Last.fm opening and Matches-switch stalls.
- https://github.com/anastaci1a/localica/issues/779 - Reuse Last.fm matching and artist photo jobs during steady scrolling.

## Known issues

- Native emulator-loss recovery remains unqualified: https://github.com/anastaci1a/localica/issues/738.

- WindowCapture test flakes: https://github.com/anastaci1a/localica/issues/734.
- DocumentsUI and artwork restoration test flakes: https://github.com/anastaci1a/localica/issues/530.
- Native popup IME test failures: https://github.com/anastaci1a/localica/issues/786.
- Last.fm group-list test flake: https://github.com/anastaci1a/localica/issues/787.
- Remaining Last.fm interaction performance: https://github.com/anastaci1a/localica/issues/780.
- Cold Last.fm matching cost: https://github.com/anastaci1a/localica/issues/794.

The lead authorizes a release waiver only for the pre-existing #786 popup IME failures and #787 group-list flake. Original failed results remain failed. All other release gates remain required. The 8.3 ms whole-frame performance target remains unmet and unchanged.

## Install

1. Download `localica-v0.33.21-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
