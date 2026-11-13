<!-- Release: v0.34.4 -->

# Release v0.34.4

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Keep a bottom-bar popup (search/…) above the mini player and tab bar for its whole plain close. Only a page takeover, such as opening Settings, hands the popup to its page.
- Export each song's latest file modification time and the modification time first seen when Localica indexed it (library data version 1.2). Neither is ever imported; 1.0 and 1.1 files still import. Songs indexed before this release record the first-seen value from their stored modification time during the upgrade.
- Remove glass sampling code left unused by the Single-Tone removal. Glass output is unchanged.
- Restructure the documentation into model-first architecture, interface and development pages.
- App Kotlin/Java source: 53,160 lines versus 53,357 in v0.34.3, a reduction of 197 lines.
- Reduce inherited UI debt from 1,474 to 1,468: RawUiMotion 346 → 344, RawPresentationTransform 134 → 132, RawShadowOrZOrder 31 → 30 and NonPresetScreenLayout 282 → 281. No new overrides.

## Resolved issues

- [#926](https://github.com/anastaci1a/localica/issues/926): A bottom-bar popup's plain close no longer retracts behind the mini player and tab bar.
- [#937](https://github.com/anastaci1a/localica/issues/937): Remove the glass sampling scaffolding left dead by the Single-Tone removal.
- [#943](https://github.com/anastaci1a/localica/issues/943): Library export carries each file's modification time, and the first-seen record keeps the modification time at first sight.
- [#929](https://github.com/anastaci1a/localica/issues/929): Restructure the documentation into small model-first pages, with rules and architecture in the repository.

## Known issues and follow-ups

- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a ratchet; new overrides are forbidden and inherited counts may only decrease.
- [#938](https://github.com/anastaci1a/localica/issues/938): An API 36 CI shard can lose its shared app/instrumentation process; exit-reason capture is in place while the cause is investigated.
- [#947](https://github.com/anastaci1a/localica/issues/947): QueueLibraryHeapTest can exhaust the 192 MB test heap when it runs late in a long shard process; release runs isolate it in its own process (the 192 MB limit is unchanged) while the cross-test retention is investigated.
- [#738](https://github.com/anastaci1a/localica/issues/738): The API 36 CI emulator can disappear mid-instrumentation.
- [#787](https://github.com/anastaci1a/localica/issues/787): LastFmGroupListTest can fail to scroll at the largest font in RTL in full runs.
- [#940](https://github.com/anastaci1a/localica/issues/940): Re-tapping Sort or View while its popup is closing is ignored instead of reopening it.
- [#920](https://github.com/anastaci1a/localica/issues/920): Reversing the bottom popup during opening can exceed its cross-axis band; reversal work remains deferred.
- [#952](https://github.com/anastaci1a/localica/issues/952): The scrollbar still waits for a hold before it activates; the two-state input model is not in this release.
- The 8.3 ms whole-frame performance objective remains unmet and unchanged. Functional acceptance does not qualify frame timing.

## Install

1. Download `localica-v0.34.4-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
