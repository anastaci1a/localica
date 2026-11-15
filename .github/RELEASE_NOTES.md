<!-- Release: v0.34.10 -->

# Release v0.34.10

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Use extracted shared contact springs and separate broad/local glow channels, preserving verified resting glass optics ([#1002](https://github.com/anastaci1a/localica/issues/1002), slices 1 and 2).
- Prepare active file-edit membership on the worker and reuse each distinct document identity during the scan.
- Initialize WorkManager when needed. Publish requested playback modes promptly while native preparation and committed settings retain their owner.
- Add AndroidX benchmark/profile tooling and recorded startup plus five journey profiles. Full profile generation and qualified A35 comparison remain follow-ups ([#986](https://github.com/anastaci1a/localica/issues/986)).
- Reuse shared playback test fixtures and pin local build-image inputs.
- Preserve the dynamic device-test inventory, refresh shard timing from complete producer 43717322169 attempt 2, and improve CI dependency reliability.
- Keep operator-authored GitHub merge commits within the identity policy ([#789](https://github.com/anastaci1a/localica/issues/789)).
- App Kotlin/Java source changes from 53,274 to 53,376 lines: 192 added, 90 removed, net +102. Accepted contact glow and playback/edit ownership account for growth.

## Resolved issues

- [#980](https://github.com/anastaci1a/localica/issues/980): Move repeated active edit-membership work off Main and retain publication ownership.
- [#1017](https://github.com/anastaci1a/localica/issues/1017): Remove eager WorkManager startup initialization.
- [#1001](https://github.com/anastaci1a/localica/issues/1001): Remove the IO-wide repeat/shuffle publication stall.
- [#679](https://github.com/anastaci1a/localica/issues/679): Remove unused release assembly from the prototype build critical path.
- [#1018](https://github.com/anastaci1a/localica/issues/1018): Retry Maven HTTP 429 responses using Retry-After.

## Known issues and follow-ups

- [#1016](https://github.com/anastaci1a/localica/issues/1016): Worker-owned Settings search is deferred after its release preflight failed to find a matching result; this release retains the previous search implementation.

- [#1002](https://github.com/anastaci1a/localica/issues/1002) and [#642](https://github.com/anastaci1a/localica/issues/642): This release includes contact spring/glow slices 1 and 2 only; later adoption and firmware-optics work remain open.
- [#986](https://github.com/anastaci1a/localica/issues/986): Player, lyrics, search and pinch profiles, complete generation/sharding, qualified plain-build A35 comparison and initial performance-ratchet adoption remain incomplete. Emulator metrics are diagnostic.
- [#1012](https://github.com/anastaci1a/localica/issues/1012): Large saved queue orders still exceed CursorWindow capacity. Million-entry in-memory acceptance does not certify cold restore.
- [#1011](https://github.com/anastaci1a/localica/issues/1011): Native playback can advance between the Main-thread check and per-edit timeline messages.
- [#957](https://github.com/anastaci1a/localica/issues/957): Million-entry diagnostics used a 256 MiB heap. Cold restore still runs out of memory at the standard 192 MiB limit; broader storage work remains open.
- [#1004](https://github.com/anastaci1a/localica/issues/1004), [#969](https://github.com/anastaci1a/localica/issues/969) and [#970](https://github.com/anastaci1a/localica/issues/970): Recovery scan, player tone and external-play work remain deferred.
- [#977](https://github.com/anastaci1a/localica/issues/977): The reported carousel lost tap remains unconfirmed; existing tap witnesses remain in the test suite.
- [#920](https://github.com/anastaci1a/localica/issues/920): Later popup lifecycle consolidation remains open.
- [#996](https://github.com/anastaci1a/localica/issues/996), [#1013](https://github.com/anastaci1a/localica/issues/1013) and [#782](https://github.com/anastaci1a/localica/issues/782): Selector and IME/focus follow-ups remain open.
- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a decreasing ratchet; new overrides are forbidden.
- [#947](https://github.com/anastaci1a/localica/issues/947): QueueLibraryHeapTest remains isolated while retention is investigated.
- The 8.3 ms whole-frame objective remains unmet and unchanged. This release makes no qualified timing claim.

## Install

1. Download `localica-v0.34.10-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
