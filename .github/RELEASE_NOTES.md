<!-- Release: v0.34.12 -->

# Release v0.34.12

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Prepare player and queue presentation on existing workers while keeping commands and Save available during preparation.
- Carry physical browse positions in the existing lazy view, removing Main-thread indexing maps from browse and drag presentation.
- Capture queue entries when Save opens, prepare queue reuse off Main, and preserve native keyboard binding in the captured-Save proof.
- App Kotlin/Java source changes from 53,451 to 53,478 lines: 213 added, 186 removed, net +27. Growth belongs to the accepted immutable presentation model.
- Refresh shard scheduling from complete producers 43743989694 and 43871578366, including risk-preflight results.

## Resolved issues

- [#995](https://github.com/anastaci1a/localica/issues/995): Remove Main-thread physical-order indexing from browse and drag presentation.
- [#1020](https://github.com/anastaci1a/localica/issues/1020): Prepare immutable player and queue presentation off Main while keeping commands available.
- [#1021](https://github.com/anastaci1a/localica/issues/1021): Keep queue summary and occurrence ownership with the prepared presentation.
- [#1026](https://github.com/anastaci1a/localica/issues/1026): Restore worker-owned queue reuse and captured Save with the accepted keyboard policy proof.
- [#1029](https://github.com/anastaci1a/localica/issues/1029): Retain the captured queue when the browsed queue changes during Save.
- [#1030](https://github.com/anastaci1a/localica/issues/1030): Keep queue commands and Save ready while presentation preparation is pending.

## Known issues and follow-ups

- [#1042](https://github.com/anastaci1a/localica/issues/1042): The Last.fm first-match persistence JVM test intermittently restores a counts-only result. Its root cause remains open.
- [#1039](https://github.com/anastaci1a/localica/issues/1039): Hosted API 36 instrumentation transport can terminate without an assertion stack. The previous release recovered through one approved failed-job retry; the transport root cause remains open.
- [#963](https://github.com/anastaci1a/localica/issues/963): Large cold restores remain slow; shuffled million-entry restore still exceeds the standard heap.
- [#964](https://github.com/anastaci1a/localica/issues/964): Broader browse and drag work remains open.
- [#1035](https://github.com/anastaci1a/localica/issues/1035): Million-entry playlist output still runs out of memory; its committed-save proof remains explicitly deferred.
- [#1033](https://github.com/anastaci1a/localica/issues/1033): Native track-switch latency remains unresolved; no A35 latency improvement is claimed.
- [#1011](https://github.com/anastaci1a/localica/issues/1011): Native playback can advance between the Main-thread check and per-edit timeline messages.
- [#1002](https://github.com/anastaci1a/localica/issues/1002), [#642](https://github.com/anastaci1a/localica/issues/642) and [#986](https://github.com/anastaci1a/localica/issues/986): Later glass adoption, optics research and qualified profile comparison remain open.
- [#1005](https://github.com/anastaci1a/localica/issues/1005) and CI tooling round 4 are outside this train.
- The 8.3 ms whole-frame A35 objective remains unmet and unchanged. This release makes no qualified frame-timing claim.

## Install

1. Download `localica-v0.34.12-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Schema 9 cannot be reopened by older schema-6, schema-7 or schema-8 APKs. Keep a backup before downgrading.
