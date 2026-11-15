<!-- Release: v0.34.9 -->

# Release v0.34.9

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Browse queue songs through revision-owned views and derive future playback through a bounded native window. Accepted [#957](https://github.com/anastaci1a/localica/issues/957) slices B and C include 1,000,000-entry diagnostic acceptance at a 256 MiB Java heap cap; restore exclusions remain below.
- Store one popup presentation state, replacing writable lifecycle flags and duplicated remembered values ([#920](https://github.com/anastaci1a/localica/issues/920), step 3a). Preserve the release frame until its window draws.
- Run twelve balanced device shards per API. Refresh scheduling from complete producers 43597960541 and 43672613455.
- Derive focused test selectors from source inventory, and export signed app/test pairs for external proofs. Build-only runs keep device checks explicitly not-run.
- Share native keyboard policy across IME tests and admit tag-editor navigation after the Library page reaches its endpoint.
- Add a JVM screenshot pilot for tabs, pinch fading and pinch retargeting, retaining all device tests and reviewed image assertions.
- Add opt-in Compose compiler reports and metrics. No application stability changes or frame-time gains are claimed.
- Add carousel witnesses for settled taps and fling-stop taps ([#977](https://github.com/anastaci1a/localica/issues/977)); the reported lost tap remains unconfirmed.
- App Kotlin/Java source changes from 53,070 to 53,274 lines: 730 added, 526 removed, net +204. Accepted queue ownership and popup-state consolidation own this growth.

## Resolved issues

- [#706](https://github.com/anastaci1a/localica/issues/706): Run twelve balanced device shards per API and require all twelve in release evidence.
- [#990](https://github.com/anastaci1a/localica/issues/990): Accept source-inventoried focused classes, including nested packages, without manual registration.
- [#967](https://github.com/anastaci1a/localica/issues/967): Admit tag-editor navigation through the existing Library endpoint helper.
- [#973](https://github.com/anastaci1a/localica/issues/973): Keep native keyboard bindings alive across shared IME policy owners.
- [#988](https://github.com/anastaci1a/localica/issues/988): Add reviewed JVM screenshot coverage for three existing Compose test classes.
- [#985](https://github.com/anastaci1a/localica/issues/985): Provide opt-in Compose compiler stability reports and a ranked inventory.
- [#974](https://github.com/anastaci1a/localica/issues/974): Consolidate native IME policy ownership in four device test classes.

## Integration

- Retain #636’s measured focused-build sizing: default 2 GB Gradle, 6 GB Kotlin and two workers. Adopt #990’s contradictory heap assertion without changing its coverage checks.

## Known issues and follow-ups

- [#1012](https://github.com/anastaci1a/localica/issues/1012): Saved queue order exceeds Android CursorWindow capacity at roughly 54,000 UUID entries. Eight real million-entry restore cases remain excluded; million-entry in-memory acceptance does not certify restoring them.
- [#1011](https://github.com/anastaci1a/localica/issues/1011): Accepted inherited race: native playback may advance between the Main-thread check and per-edit timeline messages. Editing around that boundary may affect the playing period.
- [#957](https://github.com/anastaci1a/localica/issues/957): Broader storage, restore and deferred migration remain tracked in [#963](https://github.com/anastaci1a/localica/issues/963), [#966](https://github.com/anastaci1a/localica/issues/966) and [#975](https://github.com/anastaci1a/localica/issues/975). Million-entry repeated insertion reached 255.997 MiB sampled Java heap with substantial blocking GC; no general memory margin or frame-time gain is claimed.
- [#1001](https://github.com/anastaci1a/localica/issues/1001) and [#1004](https://github.com/anastaci1a/localica/issues/1004): Mode/window publication and recovery-scan follow-ups remain outside this release.
- [#996](https://github.com/anastaci1a/localica/issues/996): Mandatory-only selector preflight ([PR #1003](https://github.com/anastaci1a/localica/pull/1003)) remains outside this cut.
- [#977](https://github.com/anastaci1a/localica/issues/977): The reported carousel lost tap remains under investigation; this release adds tests without an application fix.
- [#920](https://github.com/anastaci1a/localica/issues/920): Step 3a is included; later lifecycle consolidation remains open.
- [#969](https://github.com/anastaci1a/localica/issues/969) and [#970](https://github.com/anastaci1a/localica/issues/970): Player tone and external-play changes remain deferred.
- Test follow-ups: [#1013](https://github.com/anastaci1a/localica/issues/1013) tracks reopening during native IME hide; [#782](https://github.com/anastaci1a/localica/issues/782) tracks intermittent search-focus readiness. [#998](https://github.com/anastaci1a/localica/issues/998) names the older nested-glass fixture failure already corrected on this release base. Required producer checks remain mandatory.
- Filed intermittent device-test failures remain tracked in [#955](https://github.com/anastaci1a/localica/issues/955), [#972](https://github.com/anastaci1a/localica/issues/972), [#976](https://github.com/anastaci1a/localica/issues/976) and [#979](https://github.com/anastaci1a/localica/issues/979).
- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a decreasing ratchet; new overrides are forbidden.
- [#947](https://github.com/anastaci1a/localica/issues/947): QueueLibraryHeapTest remains isolated while retention is investigated.
- The 8.3 ms whole-frame objective remains unmet and unchanged. Functional acceptance does not qualify frame timing.

## Install

1. Download `localica-v0.34.9-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
