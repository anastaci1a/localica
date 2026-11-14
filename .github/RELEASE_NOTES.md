<!-- Release: v0.34.5 -->

# Release v0.34.5

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Localica appears in Samsung Sound Assistant’s Favourite media app list, and media play keys resume the restored queue.

- Album carousel tiles retain taps and holds when they overlap the scrollbar thumb. The scrollbar still drags.
- A fresh single-song drag keeps its selection presentation stable through release and saving, avoiding the Queue's bottom-edge jump.
- Remove redundant scrollbar keys and attachment checks. Keep existing UI debt anchors aligned with their unchanged constructs.
- App Kotlin/Java source: 53,185 lines versus 53,171 in v0.34.4, an increase of 14 lines. The media-button receiver adds six manifest lines; all app-source changes total +53/-33 (net +20). The selection lease binds to the existing drag transaction without adding mutable state.
- Inherited UI debt stays at 1,468, with zero new overrides: IndependentUiClock 86, LiteralUiSpacing 484, MotionReadInComposition 4, NonPresetScreenLayout 281, RawMotionMap 40, RawPresentationTransform 132, RawShadowOrZOrder 30, RawUiMotion 344, ReplacePersistentShape 7, UiLayerDependency 6, UnownedSurfaceLifetime 54. All other rule counts are zero.

## Resolved issues

- [#828](https://github.com/anastaci1a/localica/issues/828): Register the Media3 media-button receiver for Samsung Favourite media app discovery and existing playback resumption.

- [#949](https://github.com/anastaci1a/localica/issues/949): A fresh one-song Queue drag no longer reveals a transient selection panel and jumps at release.
- [#953](https://github.com/anastaci1a/localica/issues/953): Album carousel tiles under the scrollbar keep their normal tap and hold behavior.
- [#954](https://github.com/anastaci1a/localica/issues/954): The prefetch probe did not reproduce thumb blocking; remove redundant scrollbar keys and attachment guards.
- [#956](https://github.com/anastaci1a/localica/issues/956): The UI lint gate pairs repeated identical incidents in line order when existing anchors move.

## Known issues and follow-ups

- [PR #829 / #826](https://github.com/anastaci1a/localica/pull/829): Shuffle Previous is deferred to v0.34.6 for a fresh port and acceptance on current main.
- [PR #832 / #787](https://github.com/anastaci1a/localica/pull/832): The Last.fm test-readiness fix is deferred to v0.34.6 for a fresh port onto current main.

- [#957](https://github.com/anastaci1a/localica/issues/957): Large queues can exhaust memory during activation, dragging or saving; the million-entry case remains unresolved. The completed matched drag fixture covers 30,000 queue occurrences and 13,400 library tracks.
- [#904](https://github.com/anastaci1a/localica/issues/904): Popup capture admission can fail intermittently in API 26 instrumentation.
- [#955](https://github.com/anastaci1a/localica/issues/955): TestProcessAccessibilityLaunchTest can fail on API 26 when a framework accessibility NullPointerException escapes its guard in CI.
- [#920](https://github.com/anastaci1a/localica/issues/920): Popup opening reversal work remains pending; neither step is included in this release.
- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a ratchet; new overrides are forbidden and inherited counts may only decrease.
- [#947](https://github.com/anastaci1a/localica/issues/947): QueueLibraryHeapTest can exhaust the test heap late in a shard; release runs isolate it while retention is investigated.
- The 8.3 ms whole-frame performance objective remains unmet and unchanged. Functional acceptance does not qualify frame timing.

## Install

1. Download `localica-v0.34.5-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
