<!-- Release: v0.34.6 -->

# Release v0.34.6

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Pressing and holding the visible scrollbar thumb over a row, then dragging before the long press fires, scrolls the list. Horizontal album gestures and completed row long presses retain their behavior.
- Share one drag relanding block and remove unused imports. App Kotlin/Java source falls from 53,185 to 53,176 lines, a reduction of 9. App-source changes add 11 lines and remove 20; no state or flags are added or removed.
- Improve Last.fm test readiness, popup capture admission and native evidence directory setup.
- Rebalance CI shards using median class timings from the two preceding release producers, 43507106413 and 43546833117. The accepted profile's proof took 39 minutes 9 seconds versus 50 minutes 1 second before.
- Inherited UI debt decreases from 1,468 to 1,463 with zero new overrides: IndependentUiClock 84, LiteralUiSpacing 484, MotionReadInComposition 4, NonPresetScreenLayout 281, RawMotionMap 40, RawPresentationTransform 132, RawShadowOrZOrder 30, RawUiMotion 341, ReplacePersistentShape 7, UiLayerDependency 6, UnownedSurfaceLifetime 54. All other rule counts are zero.

## Resolved issues

- [#959](https://github.com/anastaci1a/localica/issues/959): Let a thumb hold followed by a vertical drag claim scrolling before the row long press.
- [#961](https://github.com/anastaci1a/localica/issues/961): Remove duplicated drag relanding code and unused imports.
- [#787](https://github.com/anastaci1a/localica/issues/787): Wait for the loaded Last.fm group list before scrolling in tests.
- [#706](https://github.com/anastaci1a/localica/issues/706): Refresh the CI shard duration profile to reduce producer time.
- [#904](https://github.com/anastaci1a/localica/issues/904): Require compositor admission before native shadow test captures.
- [#968](https://github.com/anastaci1a/localica/issues/968): Provision the app external-files root before native tests write evidence.

## Known issues and follow-ups

- [#957](https://github.com/anastaci1a/localica/issues/957): Large queues can exhaust memory. Slice A is held for capture-membership and whole-burst Main CPU proof; no slice is included here.
- [#967](https://github.com/anastaci1a/localica/issues/967): TagEditorFlowTest has an unresolved intermittent popup readiness failure.
- [#955](https://github.com/anastaci1a/localica/issues/955): API 26 accessibility launch instrumentation can expose a framework NullPointerException.
- [#920](https://github.com/anastaci1a/localica/issues/920) and [#960](https://github.com/anastaci1a/localica/issues/960): Accepted phase-model steps 1 and 2 are deferred to ride with the dependent Search motion work after its proof completes.
- [PR #829 / #826](https://github.com/anastaci1a/localica/pull/829): Shuffle Previous is deferred until after #957 slice C because both change QueueJourney. This is the sole open PR at the release cut.
- [#772](https://github.com/anastaci1a/localica/issues/772): The historical accepted preference subset remains coupled to blocked Main-thread enforcement work; its branch is deferred.
- Historical branch tips for [#713](https://github.com/anastaci1a/localica/issues/713), [#700](https://github.com/anastaci1a/localica/issues/700) and [#691](https://github.com/anastaci1a/localica/issues/691) are deferred: only their earlier accepted release integrations are retained; later or superseded branch revisions are outside this cut.
- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a decreasing ratchet; new overrides are forbidden.
- [#947](https://github.com/anastaci1a/localica/issues/947): QueueLibraryHeapTest can exhaust the test heap late in a shard; release runs isolate it while retention is investigated.
- The 8.3 ms whole-frame objective remains unmet and unchanged. Functional acceptance does not qualify frame timing.

## Install

1. Download `localica-v0.34.6-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
