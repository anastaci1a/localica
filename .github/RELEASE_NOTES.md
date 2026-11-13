<!-- Release: v0.34.3 -->

# Release v0.34.3

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Let a swipe-down outside the Queue minimize the player while the Queue popup is still minimizing, and route Queue sheet contacts through the shared sheet drag.
- Keep each song's date added for the life of its track record, so rescans no longer reset it and date sorting stops collapsing to A–Z ties.
- Make a tap on the current tab always reselect it, so the next tap lands.
- Remove the Single-Tone glass renderer. The glass renderer setting now offers Normal and None, and Normal is unchanged.
- Record the process exit reason when a native CI shard loses its app process.
- App Kotlin/Java source: 53,357 lines versus 53,977 in v0.34.2, a reduction of 620 lines.
- Reduce inherited UI debt from 1,480 to 1,474: RawPresentationTransform 136 → 134 and UnownedSurfaceLifetime 58 → 54. No new overrides.

## Resolved issues

- [#885](https://github.com/anastaci1a/localica/issues/885): Minimize the player with a swipe-down outside the Queue hitbox while the Queue popup is minimizing.
- [#919](https://github.com/anastaci1a/localica/issues/919): Remove the Single-Tone glass rendering mode, keeping Normal and None.
- [#930](https://github.com/anastaci1a/localica/issues/930): Preserve date added across rescans so it is never reset or shared by the whole library.
- [#931](https://github.com/anastaci1a/localica/issues/931): Fold the Queue's direct sheet-contact routing into the shared sheet drag.
- [#936](https://github.com/anastaci1a/localica/issues/936): Treat a tap on the current tab as reselection by its logical identity, so the next tap (such as a filter chip) is no longer swallowed.

## Known issues and follow-ups

- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a ratchet; new overrides are forbidden and inherited counts may only decrease.
- [#938](https://github.com/anastaci1a/localica/issues/938): An API 36 CI shard can lose its shared app/instrumentation process; this release adds exit-reason capture while the cause is investigated.
- [#787](https://github.com/anastaci1a/localica/issues/787): LastFmGroupListTest can fail to scroll at the largest font in RTL in full runs.
- [#940](https://github.com/anastaci1a/localica/issues/940): Re-tapping Sort or View while its popup is closing is ignored instead of reopening it.
- [#920](https://github.com/anastaci1a/localica/issues/920): Reversing the bottom popup during opening can exceed its cross-axis band; reversal work remains deferred.
- [#937](https://github.com/anastaci1a/localica/issues/937): Glass sampling scaffolding left unused by the Single-Tone removal is scheduled for deletion.
- The 8.3 ms whole-frame performance objective remains unmet and unchanged. Functional acceptance does not qualify frame timing.

## Install

1. Download `localica-v0.34.3-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
