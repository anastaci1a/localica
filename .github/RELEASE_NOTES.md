<!-- Release: v0.34.13 -->

# Release v0.34.13

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Add the Fluidity press response to shared glass controls, including consistent release, reversal and resize behavior.
- Keep the existing glass appearance while updating the shared press response.
- App Kotlin/Java source changes from 53,477 to 53,515 lines: 101 added, 63 removed, net +38. The shared response replaces the previous body update law.
- Refresh shard scheduling from complete v0.34.12 producer 44091537406.

## Resolved issues

- [#1005](https://github.com/anastaci1a/localica/issues/1005): Complete the shared glass press-response scope. The broader interaction programme remains open.

## Known issues and follow-ups

- [#982](https://github.com/anastaci1a/localica/issues/982): Scoped search history tests can lose the editor node during text replacement.
- [#1046](https://github.com/anastaci1a/localica/issues/1046): Inherited workflow contract failures remain in separate tooling checks.
- [#1047](https://github.com/anastaci1a/localica/issues/1047): A parallel-build scalar allocation check can fail intermittently.
- [#1049](https://github.com/anastaci1a/localica/issues/1049): Pinch slow-frame release coverage with animations enabled remains follow-up work.
- [#1026](https://github.com/anastaci1a/localica/issues/1026) and [#1029](https://github.com/anastaci1a/localica/issues/1029): Captured Save remains deferred; Save follows the currently browsed queue and eagerly maps large entry lists on Main.
- The performance ratchet remains pending. No frame-time or playback-latency improvement is claimed.

## Install

1. Download `localica-v0.34.13-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
