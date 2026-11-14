<!-- Release: v0.34.7 -->

# Release v0.34.7

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Build playback items only for the visible native queue window. One-million-entry activation succeeds within a 256 MiB diagnostic heap.
- Remove the redundant capture membership scan. A 50,000-Next diagnostic burst reduces Main CPU from 2,830 to 1,369 ms; this is not qualified frame timing.
- Apply shuffle settings to native playback immediately while resolving the window, preserving native item order.
- App Kotlin/Java source falls from 53,176 to 53,160 lines: 49 added, 65 removed, net -16. Six stored members removed; one bounded holder added; no new flag.
- Inherited UI debt stays at 1,463 with zero new overrides. IndependentUiClock 84, LiteralUiSpacing 484, MotionReadInComposition 4, NonPresetScreenLayout 281, RawMotionMap 40, RawPresentationTransform 132, RawShadowOrZOrder 30, RawUiMotion 341, ReplacePersistentShape 7, UiLayerDependency 6, UnownedSurfaceLifetime 54. Other rules remain zero.
- Refresh shard scheduling from the two preceding complete release producers, 43546833117 and 43585677410.

## Resolved issues

- [#957](https://github.com/anastaci1a/localica/issues/957), slice A: Bound playback templates to the native queue window. The issue remains open for slices B and C.

## Known issues and follow-ups

- [#963](https://github.com/anastaci1a/localica/issues/963): Queue order and journey storage still grow with queue size; million-entry transport can exhaust memory.
- [#966](https://github.com/anastaci1a/localica/issues/966): Queue position mapping and reordered projections need follow-up work.
- [#957](https://github.com/anastaci1a/localica/issues/957): Slices B and C remain pending; browse catalogs and queue journey work are outside slice A.
- [#967](https://github.com/anastaci1a/localica/issues/967): TagEditorFlowTest has an unresolved intermittent popup readiness failure.
- [#955](https://github.com/anastaci1a/localica/issues/955): API 26 accessibility launch instrumentation can expose a framework NullPointerException.
- [#920](https://github.com/anastaci1a/localica/issues/920) and [#960](https://github.com/anastaci1a/localica/issues/960): Accepted branches `fix/920-step1-derived-phase` and `fix/920-step2-takeover` are deferred to ride with the dependent Search motion work on `fix/960-search-shared` after its proof completes.
- [PR #829 / #826](https://github.com/anastaci1a/localica/pull/829): Shuffle Previous is deferred until after #957 slice C because both change QueueJourney. This is the sole open PR at the release cut.
- [#772](https://github.com/anastaci1a/localica/issues/772): The historical accepted preference subset remains coupled to blocked Main-thread enforcement work; branch `agent122/main-thread-772` is deferred.
- Historical branch tips for [#713](https://github.com/anastaci1a/localica/issues/713), [#700](https://github.com/anastaci1a/localica/issues/700) and [#691](https://github.com/anastaci1a/localica/issues/691) (`feat/stage3-glass-toast-713`, `feat/tag-editor-save-progress-700-agent91`, `fix/cheaper-live-691`) are deferred: only their earlier accepted release integrations are retained; later or superseded branch revisions are outside this cut.
- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a decreasing ratchet; new overrides are forbidden.
- [#947](https://github.com/anastaci1a/localica/issues/947): QueueLibraryHeapTest can exhaust the test heap late in a shard; release runs isolate it while retention is investigated.
- The 8.3 ms whole-frame objective remains unmet and unchanged. Functional acceptance does not qualify frame timing.

## Install

1. Download `localica-v0.34.7-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
