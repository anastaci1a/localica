<!-- Release: v0.34.9 -->

# Release v0.34.9

Android APK for Android 8 or newer. Keeps the existing `.debug` application ID and project development signing key for in-place updates. Separate production signing is not configured.

This optimized, non-debuggable build uses R8 and updates the existing app in place.

## Changes

- Browse queue songs through revision-owned views instead of copying each row. This delivers accepted slice B of [#957](https://github.com/anastaci1a/localica/issues/957); the issue remains open.
- Run twelve balanced device shards per API. Refresh scheduling from complete producers 43597960541 and 43672613455.
- Derive focused test selectors from source inventory, and export signed app/test pairs for external proofs. Build-only runs keep device checks explicitly not-run.
- Share native keyboard policy across IME tests and admit tag-editor navigation after the Library page reaches its endpoint.
- Add a JVM screenshot pilot for tabs, pinch fading and pinch retargeting, retaining all device tests and reviewed image assertions.
- Add opt-in Compose compiler reports and metrics. No application stability changes or frame-time gains are claimed.
- Add carousel witnesses for settled taps and fling-stop taps ([#977](https://github.com/anastaci1a/localica/issues/977)); the reported lost tap remains unconfirmed.
- App Kotlin/Java source changes from 53,070 to 53,112 lines: 151 added, 109 removed, net +42. The accepted queue-view model owns this growth.

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

- [#957](https://github.com/anastaci1a/localica/issues/957): Slice C is reserved for later acceptance before the release producer. Queue storage and restore limits remain tracked in [#963](https://github.com/anastaci1a/localica/issues/963), [#966](https://github.com/anastaci1a/localica/issues/966) and [#975](https://github.com/anastaci1a/localica/issues/975).
- [#996](https://github.com/anastaci1a/localica/issues/996): Mandatory-only selector preflight awaits integration from the main-branch merge of [PR #1003](https://github.com/anastaci1a/localica/pull/1003).
- [#977](https://github.com/anastaci1a/localica/issues/977): The reported carousel lost tap remains under investigation; this release adds tests without an application fix.
- [#920](https://github.com/anastaci1a/localica/issues/920): Step 3a and later lifecycle consolidation remain outside this cut.
- [#969](https://github.com/anastaci1a/localica/issues/969) and [#970](https://github.com/anastaci1a/localica/issues/970): Player tone and external-play changes remain deferred.
- Filed intermittent device-test failures remain tracked in [#955](https://github.com/anastaci1a/localica/issues/955), [#972](https://github.com/anastaci1a/localica/issues/972), [#976](https://github.com/anastaci1a/localica/issues/976) and [#979](https://github.com/anastaci1a/localica/issues/979).
- [#895](https://github.com/anastaci1a/localica/issues/895): Existing UI architecture findings remain under a decreasing ratchet; new overrides are forbidden.
- [#947](https://github.com/anastaci1a/localica/issues/947): QueueLibraryHeapTest remains isolated while retention is investigated.
- The 8.3 ms whole-frame objective remains unmet and unchanged. Functional acceptance does not qualify frame timing.

## Install

1. Download `localica-v0.34.9-pre-release.apk` from **Assets** below (Android 8+).
2. Open the APK and allow installation from your browser or file manager if prompted.
3. First use: open **More → Scan music**, choose **Folders to scan**, then **Scan now**.
