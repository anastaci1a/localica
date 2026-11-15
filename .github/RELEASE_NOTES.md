<!-- Release: v0.34.8 -->

# Release v0.34.8

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Move Search pill motion onto the shared popup model and Dialog motion law. Dismiss logically at the input event while retaining physical presentation through child handoff.
- Deliver [#920](https://github.com/anastaci1a/localica/issues/920) steps 1 and 2: derive the lifecycle phase once and replace takeover flags with views. The issue remains open.
- App Kotlin/Java source falls from 53,160 to 53,070 lines: 234 added, 324 removed, net -90. Release engineering adds no app state.
- Inherited UI debt falls from 1,463 to 1,459 with zero new overrides. RawUiMotion falls from 341 to 337; all other rule counts stay unchanged.
- Keep popup presentation ownership local to each flight, including cancellable waits for native-window detachment. The reviewed repair adds one current-flight reference and no flags.
- Refresh shard scheduling from the preceding complete producer, 43597960541. Keep the current eight-shard Android workflow.

## Resolved issues

- [#636](https://github.com/anastaci1a/localica/issues/636): Give focused hosted instrumentation prebuilds sufficient Kotlin heap.

- [#960](https://github.com/anastaci1a/localica/issues/960): Unify Search / ⋯ pill expansion and retraction with the shared popup model.

## Known issues and follow-ups

- [#957](https://github.com/anastaci1a/localica/issues/957): Slice B is approved with focused proofs pending; slice C is in implementation. Both are deferred.
- [#920](https://github.com/anastaci1a/localica/issues/920): Step 3a is deferred while a test hang is diagnosed. Later lifecycle consolidation remains pending.
- [#969](https://github.com/anastaci1a/localica/issues/969) and [#970](https://github.com/anastaci1a/localica/issues/970): Player tone and external-play fixes are deferred while proof completes.
- [#977](https://github.com/anastaci1a/localica/issues/977): Album carousel taps after scrolling remain under investigation; the fix is deferred.
- [#706](https://github.com/anastaci1a/localica/issues/706): Twelve-shard scheduling is deferred while failure classification completes. This release retains eight shards per API.
- [#973](https://github.com/anastaci1a/localica/issues/973): SettingsRootSearchTest readiness fix is deferred pending proof.
- Filed intermittent device-test failures remain tracked in [#955](https://github.com/anastaci1a/localica/issues/955), [#967](https://github.com/anastaci1a/localica/issues/967), [#972](https://github.com/anastaci1a/localica/issues/972), [#976](https://github.com/anastaci1a/localica/issues/976) and [#979](https://github.com/anastaci1a/localica/issues/979).
- [#963](https://github.com/anastaci1a/localica/issues/963) and [#966](https://github.com/anastaci1a/localica/issues/966): Queue order, journey storage and mapping still require bounded-memory work.
- [#975](https://github.com/anastaci1a/localica/issues/975): Restoring a million-entry queue can exhaust memory. The restore implementation is deferred behind queue storage work.
- [PR #829 / #826](https://github.com/anastaci1a/localica/pull/829): Shuffle Previous is deferred until after #957 slice C because both change QueueJourney. This is the sole open PR.
- [#772](https://github.com/anastaci1a/localica/issues/772): The historical accepted preference subset remains coupled to blocked Main-thread enforcement work; branch `agent122/main-thread-772` is deferred.
- Historical branches `feat/stage3-glass-toast-713`, `feat/tag-editor-save-progress-700-agent91` and `fix/cheaper-live-691` are deferred: earlier accepted integrations remain; later or superseded revisions are outside this release.
- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a decreasing ratchet; new overrides are forbidden.
- [#947](https://github.com/anastaci1a/localica/issues/947): QueueLibraryHeapTest can exhaust the test heap late in a shard; release runs isolate it while retention is investigated.
- The 8.3 ms whole-frame objective remains unmet and unchanged. Functional acceptance does not qualify frame timing.

## Install

1. Download `localica-v0.34.8-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
