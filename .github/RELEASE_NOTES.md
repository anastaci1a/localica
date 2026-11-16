<!-- Release: v0.34.11 -->

# Release v0.34.11

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Prepare playback intake, Shuffle, queue reuse, metadata refresh and captured playlist-save inputs on existing workers, preserving occurrence and admission order.
- Store saved Manual order as occurrence ranks instead of one large JSON row; migrate older saved orders through the shared bounded reader.
- Prepare Settings search off Main, retaining completed results while a replacement query computes. Update caller tests to await the rendered search target.
- Reuse immutable browse views and compare filter effect keys by snapshot ownership; broader browse scalability remains open.
- App Kotlin/Java source grows from 53,376 to 53,455 lines: 172 added, 93 removed, net +79. Ranked durable order and accepted worker ownership account for growth.
- Refresh shard scheduling from both preceding complete release producers, including risk-preflight results.

## Resolved issues

- [#1012](https://github.com/anastaci1a/localica/issues/1012): Remove the oversized saved-order row that exceeded CursorWindow capacity.
- [#1016](https://github.com/anastaci1a/localica/issues/1016): Move catalog normalization and Settings search matching off Main.
- [#1022](https://github.com/anastaci1a/localica/issues/1022): Prepare admitted playback sources on workers while preserving FIFO order.
- [#1023](https://github.com/anastaci1a/localica/issues/1023): Borrow pinned-song playback input without a Main-thread source copy.
- [#1024](https://github.com/anastaci1a/localica/issues/1024): Prepare collection Shuffle on the existing command worker.
- [#1025](https://github.com/anastaci1a/localica/issues/1025): Remove the controller-free browse Shuffle path.
- [#1026](https://github.com/anastaci1a/localica/issues/1026): Compare reusable queue snapshots on the existing worker.
- [#1027](https://github.com/anastaci1a/localica/issues/1027): Prepare pending queue input off Main at the saved-queue limit.
- [#1028](https://github.com/anastaci1a/localica/issues/1028): Rebuild refreshed saved-queue metadata off Main.
- [#1029](https://github.com/anastaci1a/localica/issues/1029): Borrow captured playlist-save input and prepare it on the worker.

## Known issues and follow-ups

- [#1020](https://github.com/anastaci1a/localica/issues/1020), [#1021](https://github.com/anastaci1a/localica/issues/1021) and [#1030](https://github.com/anastaci1a/localica/issues/1030): Player and queue presentation work is deferred after queue Save readiness failed its release proof.

- [#995](https://github.com/anastaci1a/localica/issues/995) and [#964](https://github.com/anastaci1a/localica/issues/964): This release includes view reuse and the filter-key correction only; broader browse and drag work remains open.
- [#963](https://github.com/anastaci1a/localica/issues/963): Large cold restores remain slow; shuffled million-entry restore still exceeds the standard heap. The schema fix does not certify restore responsiveness.
- [#1035](https://github.com/anastaci1a/localica/issues/1035): Million-entry playlist output still runs out of memory; its committed-save proof remains explicitly deferred.
- [#1033](https://github.com/anastaci1a/localica/issues/1033): Native track-switch latency remains unresolved. Disposable-emulator intervals exceeded 100 ms; no A35 latency improvement is claimed.
- [#1011](https://github.com/anastaci1a/localica/issues/1011): Native playback can advance between the Main-thread check and per-edit timeline messages.
- [#1002](https://github.com/anastaci1a/localica/issues/1002), [#642](https://github.com/anastaci1a/localica/issues/642) and [#986](https://github.com/anastaci1a/localica/issues/986): Later glass adoption, optics research and qualified profile comparison remain open.
- [#1005](https://github.com/anastaci1a/localica/issues/1005) and CI tooling round 4 are outside this train.
- The 8.3 ms whole-frame A35 objective remains unmet and unchanged. This release makes no qualified frame-timing claim.

## Install

1. Download `localica-v0.34.11-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Schema 9 cannot be reopened by older schema-6, schema-7 or schema-8 APKs. Keep a backup before downgrading.
