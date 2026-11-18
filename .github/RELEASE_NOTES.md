<!-- Release: v0.34.15 -->

# Release v0.34.15

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Glass backdrop blur strength now follows display density: lighter on very dense displays, slightly stronger on less dense ones.
- App Kotlin/Java source changes from 53,514 to 53,520 lines: 9 added, 3 removed, net +6. One shared density-based radius replaces the fixed blur radius, and one obsolete lint baseline entry is removed.
- Refresh shard scheduling from complete v0.34.14 and v0.34.13 producers 44336375980 and 44221675774.

## Resolved issues

- [#1005](https://github.com/anastaci1a/localica/issues/1005): Glass backdrop blur strength follows display density in the shared glass surface. The broader interaction programme remains open.

## Known issues and follow-ups

- [#982](https://github.com/anastaci1a/localica/issues/982): Scoped search history tests can lose the editor node during text replacement.
- [#1047](https://github.com/anastaci1a/localica/issues/1047): A parallel-build scalar allocation check can fail intermittently.
- [#1049](https://github.com/anastaci1a/localica/issues/1049): Pinch slow-frame release coverage with animations enabled remains follow-up work.
- [#1051](https://github.com/anastaci1a/localica/issues/1051): Library data import confirmation has an intermittent Android 8 timeout history; the dialog replacement fix is included, with the known timeout disclosed for release acceptance.
- [#530](https://github.com/anastaci1a/localica/issues/530): The artwork chooser can intermittently time out waiting for “Choose recent image” in ArtworkEditRestorationTest.
- [#1061](https://github.com/anastaci1a/localica/issues/1061): A historical held-drag playback test timed out waiting for idle. That test path has been removed; current cover assertions remain mandatory.
- [#1063](https://github.com/anastaci1a/localica/issues/1063): Investigate count validity when shuffle or repeat changes; this source finding has not been reproduced on a device.
- [#1064](https://github.com/anastaci1a/localica/issues/1064): Add pending-scroll coverage for the list scrollbar path.
- [#1065](https://github.com/anastaci1a/localica/issues/1065): Remove obsolete shuffle test diagnostics while preserving native refresh and assertions.
- [#1066](https://github.com/anastaci1a/localica/issues/1066): Static frosted panels such as the queue drawer still use the previous fixed blur radius.
- [#1067](https://github.com/anastaci1a/localica/issues/1067): Blur radius outside the 2.0–3.0 density range changes without a reference value.
- [#1068](https://github.com/anastaci1a/localica/issues/1068) and [#1069](https://github.com/anastaci1a/localica/issues/1069): Blur test clean-up and removal of one unreachable fallback branch.
- [#1052](https://github.com/anastaci1a/localica/issues/1052), [#1053](https://github.com/anastaci1a/localica/issues/1053) and [#1054](https://github.com/anastaci1a/localica/issues/1054): Filed follow-ups cover shared glass complexity accounting, control ownership, and moving-contact and first-frame press response.
- [#1026](https://github.com/anastaci1a/localica/issues/1026) and [#1029](https://github.com/anastaci1a/localica/issues/1029): Captured Save remains deferred; Save follows the currently browsed queue and eagerly maps large entry lists on Main.
- [#1042](https://github.com/anastaci1a/localica/issues/1042): The Last.fm first-match persistence JVM test intermittently restores a counts-only result. Its root cause remains open.
- [#1039](https://github.com/anastaci1a/localica/issues/1039): Hosted API 36 instrumentation transport can terminate without an assertion stack. Its root cause remains open.
- [#963](https://github.com/anastaci1a/localica/issues/963): Large cold restores remain slow; shuffled million-entry restore still exceeds the standard heap.
- [#964](https://github.com/anastaci1a/localica/issues/964): Broader browse and drag work remains open.
- [#1035](https://github.com/anastaci1a/localica/issues/1035): Million-entry playlist output still runs out of memory.
- [#1033](https://github.com/anastaci1a/localica/issues/1033): Native track-switch latency remains unresolved; no A35 latency improvement is claimed.
- [#1011](https://github.com/anastaci1a/localica/issues/1011): Native playback can advance between the Main-thread check and per-edit timeline messages.
- [#1002](https://github.com/anastaci1a/localica/issues/1002), [#642](https://github.com/anastaci1a/localica/issues/642) and [#986](https://github.com/anastaci1a/localica/issues/986): Later glass adoption and qualified profile comparison remain open.
- The performance ratchet remains pending. No frame-time or playback-latency improvement is claimed.
- The 8.3 ms whole-frame A35 objective remains unmet and unchanged. This release makes no qualified frame-timing claim.

## Install

1. Download `localica-v0.34.15-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.

Schema 9 cannot be reopened by older schema-6, schema-7 or schema-8 APKs. Keep a backup before downgrading.
