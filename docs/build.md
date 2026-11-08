# Build and release

## Local verification

Use JDK 17, Android platform 36 and build tools 35.0.0. Set `ANDROID_HOME` or untracked `local.properties` with `sdk.dir=/path/to/sdk`. The pinned wrapper verifies its download checksum.

Register the merge driver for `.github/prototype-risk-classes.txt` once per clone: `git config merge.union-dedupe.driver 'sh tools/git/merge-union-dedupe.sh %O %A %B'`. It merges both sides' classes and drops duplicate lines, which the risk inventory contract rejects.


### Development and final validation

The Localica lead runs effective tests and test setup without separate user approval. Use focused local checks while implementing individual changes. Documentation-only changes need prose, structure, link and rendering checks. Run the complete suite only after all intended changes form a ready final candidate.

Select tests for migrations, failed operations, occurrence/cursor/metadata preservation, navigation restoration, gestures and playback/lyrics/artwork boundaries. Cover accessibility, short/landscape screens, large fonts and reduced motion.

For repeated `compileDebugAndroidTestKotlin` or `assembleDebug` runs, opt in with `--configuration-cache --configuration-cache-problems=fail --no-daemon --max-workers=2`. Run selected tests separately. Verify other task graphs before adopting the cache.

If full validation fails, preserve its first evidence. Repair failures with affected focused tests. Once all repairs are ready, freeze the new candidate and rerun full validation. Investigate every observed failure, including one from a slower parallel run.

One verified complete equivalent pass counts for the exact candidate. A duplicate local and GitHub pass is not routinely required. Changes to code, tests or build inputs require fresh affected checks. Final validation applies to the frozen candidate.

| Stage | Required evidence |
| --- | --- |
| Development | Focused commands, immutable source, environment, duration and per-test outcomes. |
| Final candidate | Build/checks, complete native API 26/36 suites and the applicable [compatibility gate](#compatibility-and-dependencies). |
| Reused result | Same immutable code, tests, build inputs, API coverage and assertions; complete passing reports. |
| Failure repair | Original failure evidence, focused repair results, then a new complete candidate result. |

Final build and native commands, run only after candidate freeze:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --no-daemon --max-workers=2
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2
```

Use the [shared Compose test factories](../app/src/androidTest/java/io/github/anastaci1a/localica/TestComposeRules.kt) for instrumentation. They select `StandardTestDispatcher` by default and preserve explicit fixture contexts. Compose 1.10 drains due work on Main while idling, including with a paused frame clock. Keep clock advances and input assertions explicit; do not add a separate idle-drain backport.

The system keyboard is device state that survives app resets between shard classes. Classes that show it through real text entry start with [`FreshSystemIme`](../app/src/androidTest/java/io/github/anastaci1a/localica/SystemImeIsolation.kt), which restarts the selected system IME process. After a long API 36 shard, LatinIME on the hardware-keyboard emulator can report its whole window as the IME inset while drawing nothing (#638).

FloatingImeLifecycleTest swaps the default IME to its synthetic service and back, which was found to leave LatinIME in that state, so it restarts the system keyboard itself in cleanup (`restartSystemKeyboard`). Tests that change the default IME must do the same.

The R8 rules preserve Compose 1.10's `AndroidLayoutApi34` helper as an optimization boundary, while allowing shrinking and renaming. This keeps its guarded text-range adapter out of paragraph classes loaded on API 26. Recheck the rule when upgrading Compose or R8; the distribution smoke linkage gate remains unchanged.

Mandatory native regressions retain presented-pixel, lifecycle, first/reopened-frame, interruption and disposal assertions. Repeated utility-popup optical recording is opt-in with `utilityPopupOpticalDiagnostics=true`. The three mandatory scene methods still run by default. Successful SwitchTap frame exports require `switchTapOpticsReview=true`. Pixel checks and bounded failure evidence remain enabled.

`ScrollPerformanceTest` only measures scrolling. It is skipped by assumption unless `scrollPerformance=true`, set by the Android workflow dispatch input `scroll_performance`. Default runs report its three methods as skipped.

Selection outline benchmarks report geometry and path construction, including mixed-opacity subset paths and unchanged-input caching. API 26 CI requires a median at most 1 ms and a 95th percentile at most 2 ms. API 36 reports timing and its renderer without enforcing that budget.

For local diagnostics under host contention, pass instrumentation argument `selectionOutlineTimingGate=false`. Measurements and cache-correctness assertions still run. CI leaves this argument unset, so the API 26 timing gate remains enabled.

`StartupDiagnosticsSeedTest` also skips by assumption without `startupDiagnostics=disposable-emulator`. Its one method prepares the diagnostic fixture; it is not performance acceptance.

The [startup diagnostic controller source](../tools/startup-diagnostics/run.py) runs in the test package, outside the measured process. Explicit disposable-emulator admission permits fixture seeding and paired process-cold/warm activity launches. It records p50/p95/p99 frame times, accessibility input milestones and optional system traces. Synthetic embedded covers exercise decoding; physical-device acceptance remains open.

Select diagnostic methods explicitly, never with a wildcard exclusion that might remove correctness tests.

Build-report artifacts include raw JVM XML under `jvm-xml/<run>/testDebugUnitTest`, including when tests fail.

Select the API 26 or 36 emulator explicitly with `ANDROID_SERIAL`. Match the [emulator configuration](#ci-and-signing). Physical-device review supplements automation. Installable development APK: `app/build/outputs/apk/debug/app-debug.apk`. The production release variant is unsigned. Test-only fixtures never ship in the app.

Standalone test-package components cannot rely on the target app classloader: the test APK packages identical bytes from the appâs resolved Kotlin standard library. Its coordinate and SHA256 are retained in `app/build/generated/standalone-test-runtime/binding.txt`. `NavigationStandaloneRuntimeTest` checks linkage in the separate provider process. Ordinary instrumentation alone does not cover that path.

### Evidence tooling prerequisites

The [host contract workflow](../.github/workflows/tooling-contracts.yml) runs the Python selection/observer contracts and Node publisher mocks without an Android device. Its pinned Python dependency targets CPython 3.12 on Linux x86-64. Use the hash-locked [requirements](../.github/tests/requirements.txt) in an isolated environment.

Node 24 installs the publisher dependencies with `npm ci --prefix .github/test-evidence --ignore-scripts --no-audit --no-fund`. The committed lockfile owns their versions. The workflow owns the exact contract commands and records each result, log hash and tested source.

These are evidence-tooling prerequisites, separate from the Gradle/JDK/SDK setup above. Offline contract passes validate mocks and protocol handling, not Android outcomes or live artifact delivery.

### Coordinated local and hosted comparison

For the next two final candidates, coordinate equivalent local and GitHub full checks in parallel to measure duration. Freeze the branch while either run is active. Record the full SHA, commands, toolchain/image identities, signing certificate, environment, start/end times, durations, outcomes and artifact hashes.

| Responsibility | Owner |
| --- | --- |
| Freeze candidate and establish equivalence | Localica lead with the parent coordinator. |
| Start local checks | Localica lead. |
| Start hosted checks and identify run | Assigned hosted-run owner. |
| Approve redundant-run cancellation | Parent coordinator with the affected run owner. |
| Preserve and investigate any observed failure | Localica lead and failing-run owner. |

Cancel only a coordinated redundant run after a complete passing result is verified. An observed failure remains unresolved evidence even if the other run passes. Never select only passing jobs from incompatible runs. Do not cancel another owner's jobs independently.

The comparison does not alter CI, branch protection, deployment or [release publication gates](#release-preparation). Existing hosted publication checks remain enforced. A local result does not by itself satisfy the current release job's hosted-run lookup.

A workflow dispatch may supply `risk_classes` as up to 20 comma-separated instrumentation classes (4096 characters maximum). The hosted disposable job freezes the complete source inventory, APK hashes and API-qualified method partition. Preflight must execute exactly its expected methods with no failures or skips before the app/test-only reset and remaining suite using `notClass`.

Archived preflight XML and remaining XML must form the complete disjoint union, with every applicable method once. Missing/extra/duplicate outcomes, source/APK drift or unsupported SDK omissions fail closed. Original assumptions remain explicit skips with their observed logs, not passes. The partition and union receipts stay in `preflight-reports`. Native remainder XML alone is not full-suite evidence.

Failed preflight stops before the remainder and preserves its status/log/reports. Empty input and ordinary PR/main runs remain unfiltered. Compatibility keeps its existing preserved-data phases.

Complete native workflow jobs enable `LOCALICA_LIVE_EVIDENCE=true`: the existing observer and supervised publisher preserve provisional failure observations while final XML owns test outcomes. Both optional preflight and full runs publish from separate fresh directories.

Full starts only after a successful preflight certifies zero observed failures and zero uploaded packets through matching observer/supervisor/publisher receipts. Contradictory or degraded evidence stops the job. Thus either failed preflight or full consumes at most six live packets total.

Monitoring degradation fails an otherwise green run. Test failure and cancellation retain their exit precedence, and owned package restoration runs after the supervisor finishes. Before native failure teardown, read-only crash-buffer and window/activity snapshots retain external error-window evidence with per-command status. Cancellation or host loss can still prevent them.

Fresh `native-live-evidence` is always uploaded, including on failure. Existing evidence is refused rather than replayed. Local invocations without this workflow flag keep their existing behavior. Compatibility remains unwrapped.

### Measured native shards

The eight native shards per API assign whole classes by measured duration, longest first. The [duration profile](../.github/test-evidence/class-durations.csv) records v0.33.7's complete run 42381322652. Previous profiles used v0.33.6 run 42361455770, v0.33.1 run 42085439724 (#598), v0.33.0 run 41980502454 (#598), v0.32.0 run 41939345197 (#591), and earlier measurements (#550).

Each complete run generates a profile in `complete-verification` for the next [release preparation](#release-preparation). The refresh helper also accepts multiple run directories and takes each class's median duration. Refresh after adding slow classes: an unmeasured six-minute class unbalanced a v0.32.0 shard. The budget is build 10 minutes, slowest shard 22 minutes, whole run 40 minutes.

Each device artifact retains `native-shard-plan.json`: source/profile hashes, class assignments, expected methods, estimated loads and fallback costs. New classes use the measured median per-method cost. Historical ScrollPerformance timings apply only when its existing opt-in is enabled; ordinary runs budget its recorded assumptions separately.

Risk preflight retains its existing method partition. Weighted remainder shards exclude those risk classes. The summary still requires every API-qualified method exactly once, including recorded skips; missing, extra, repeated or failed outcomes cannot certify the full suite.

### Opt-in prototypes

Each prototype occupies at most two runners: one chain runs build and both compatibility checks; the other runs both focused APIs. A failed check does not skip later checks in its chain. Release coordination still holds new prototype dispatches until release shards start, reducing competition for the runner pool.

The opt-in `prototype.yml` lane builds an explicitly uncertified development APK through manual dispatch or an explicit reusable-workflow call. Select up to 73 affected behavior classes (4096 characters maximum including the three mandatory regression classes) and describe coverage and limitations.

Full native shards and focused prototype jobs time out after 35 minutes. A timed-out shard fails the summary even if its XML is complete; rerun failed jobs to retry it.

The hosted emulator's adb transport can drop while UTP pulls outputs and uninstalls the APKs after a complete, failure-free run (#675). The prototype runner then waits for the same guest, finishes the interrupted uninstall and reconciles the first attempt's final XML as usual. It never runs the gate again, which would give every test a second chance.

Recovery requires a final XML with every expected method and no failures, no observed failure line, a Gradle failure caused only by that teardown plugin error, complete monitoring, and the same guest (unchanged boot id) answering adb within three minutes. The repeat summary records it under `prototype-evidence/teardown-offline/`.

Any other failure, a rebooted guest, a device that stays offline, a package that cannot be removed or an exact-method iteration keeps the original failure.

Search IME, search-focus and contrast coverage is mandatory on API 26/36. Build/JVM/lint/signature and both preserved-data compatibility jobs also remain required. Known issues never waive failures.

The `runner` input defaults to `hosted`. `runner=self-hosted` moves only the two focused emulator jobs to the owner’s locked-down single-job runner, for the owner’s manual dispatch on a branch (#592). A queued self-hosted job waits for the owner’s launcher and is never rerouted.

Self-hosted runs focused jobs with `repeat` up to 5 (with or without `repeat_methods`); longer repeat proofs stay hosted. Build, compatibility and evidence jobs, `android.yml`, release gates and acceptance runs always stay hosted.

The prototype build job compiles debug and release Kotlin concurrently in one Kotlin daemon, which inherits the Gradle heap. It therefore uses the Android static-checks sizing (`-Xmx6g`, four workers) on the 16 GB hosted runner; the 2 GB local default ran out of memory there (#636).

The decision manifest records the exact SHA, APK identity, selected coverage and that complete native suites were not run. Prototype results do not certify a release.

Prototypes use the same [evidence tooling](#evidence-tooling-prerequisites), six-packet limit and outcome rules described above: final XML owns test results, monitoring degradation blocks a green result, and test failure retains its exit status.

No automatic replay or replacement full-suite run is started. Hosted transport evidence must identify the tested publisher revision. Offline contract success alone does not prove live delivery. New manual workflows require registration or an explicitly coordinated branch smoke before use.

### UI frame measurements

The opt-in [UI suite](../app/src/androidTest/java/io/github/anastaci1a/localica/UiPerformanceTest.kt) measures real Window frames with 2,000 seeded tracks and placeholder artwork. It covers list densities, gallery columns, carousels, subviews, Queue, pinch, selection/drag, popups, player, lyrics/theme and search.

Install matching debug and test APKs on an owned disposable emulator. Run the [measurement script](../tools/performance/run-ui-benchmarks.sh) with its serial, a fresh evidence directory and the full source SHA:

```sh
tools/performance/run-ui-benchmarks.sh emulator-5554 /path/to/evidence FULL_SOURCE_SHA
```

Run API 26 and 36 separately. The script records one warmup and three repetitions, ranks scenarios by p99, and captures separate atrace passes for the three slowest scenarios. Traced passes do not replace baseline samples.

The suite selects None when available. Older sources use Modern with blur disabled; API 26 resolves that request to Legacy. These provisional results retain renderer settings and cannot establish glass-free performance.

Frame duration percentiles use nearest rank. Jank means duration exceeds the sampled display refresh period. Missed vsyncs are duration-based estimates. Deadline misses are reported separately on API 31+. Listener drops, overflow and incomplete native delivery remain explicit.

Emulators are proxies. For pure-UI comparison, select None when available. On a physical device, open More â Settings â About and licenses. Start frame capture, repeat an action, return to stop capture, then Export diagnostics. Capture stops after two minutes or Activity destruction; a new capture replaces the previous sample.

Capture is opt-in, bounded and process-local. Export includes app/device identity, renderer settings and per-Window summaries, without music titles, paths or search text. It excludes system keyboard frames. Received Window timings do not measure presentation latency.

## Compatibility and dependencies

Queue schema 7 cannot be reopened by the original schema-6 APK. Before distributing it, dispatch the exact candidate through the Android workflow with `compatibility=true`. Its API 26/36 gate installs candidate â legacy-behavior bridge â candidate with the same package/signature and increasing private version codes, without clearing data.

The [isolated overlay](../.github/compatibility/prepare.py) retains current schema, journal and recovery safety. Only traversal behavior changes. `ShuffleApkCompatibilityTest` checks duplicate occurrences, saved order/cursor, controller reopening and statistics across `seed`, `bridge`, then `verify`. Bridge journal/recovery checks run separately. Compatibility provenance binds the archived candidateâs reviewed instrumentation runner. APK manifest checks and every invocation require that exact runner without resident-session opt-in.

Private compatibility artifacts are never distributed: use the canonical `localica-debug` APK. Ordinary migration tests do not replace this gate. Track exact-source evidence in [#295](https://github.com/anastaci1a/localica/issues/295).

Finish tag editing, recovery and pending metadata refreshes before downgrading: older builds cannot read the incremental metadata-refresh records. The existing array journal migrates on upgrade. Queue database schemas are independent.

Android 8+. MP3 and AAC/M4A are primary formats, with WAV/FLAC/Ogg/Opus using available device decoders. No decoder extensions. Boundary tests check decoded samples, MP3 waveform alignment and transition buffering. Sample counts alone are insufficient.

FLAC tests require a decoder. Stock Android supplies one from API 27.

The [AAC edit-list correction](structure.md#aac-edit-list-trimming) covers bounded shortened-sample and rounded-endpoint layouts. Unsupported M4A timing retains native behavior.

Private-file diagnostics are opt-in. Keep inputs and decoded audio private and untracked. Record observer costs and native output failures separately from sample comparisons. None proves physical speaker continuity.

MP3 boundary checks require exact sample counts and unshifted waveforms on Android 8 and modern decoders. Additional regressions preserve intentional trailing silence and verify seek resets during a deferred transition.

Media3 stays pinned to 1.10.1 pending a stable [MP3 trimming fix](https://github.com/androidx/media/issues/3425). Rerun waveform checks before upgrading.

Jaudiotagger 3.0.1 uses LGPL 2.1+. Bundle its notice/license and include its source archive with releases. To replace it, build the source with Maven, replace `implementation(libs.jaudiotagger)` with the local JAR, and rebuild under your signing key. Localica remains MIT licensed.

## CI and signing

The [Android workflow](../.github/workflows/android.yml) builds application changes on main/PR and tests API 26/36. Match its two virtual CPU cores and 3,072 MiB emulator RAM locally.

Three jobs start together: `test-apks` builds the debug app and instrumentation APKs that every shard installs, `build` builds only the R8 delivery APK (smoke tests and the release artifact), and `static-checks` runs unit tests, lint and the release build. The shards wait only for `test-apks`; the summary and verified-run reuse require all three.

One producer (`test-apks`) builds the native debug app/test APKs, metadata, class inventory and standalone runtime binding; the separate `build` job produces the delivery artifact. Every shard verifies their source/run identity and digests, then runs AGP instrumentation with build dependency actions disabled. APK hashes are checked again after instrumentation.

Documentation-only changes use the separate documentation workflow.

The native job splits each API suite into eight AndroidJUnitRunner shards (`numShards`/`shardIndex`) on separate emulators with the same profile. Evidence and report artifacts end in `-shard-N`.

`device-tests-summary` downloads every shard XML. It fails unless each API's union equals the source inventory exactly once, skipped assumptions included, and every shard passed. With `risk_classes`, each shard runs its slice of those classes first, then its slice of the remainder.

API 36 uses explicit `lavapipe`, 720Ã1280 at 280 dpi, and normal system animations: automatic software/SwiftShader caused rendering crashes. Zero-duration system transitions stalled launcher/Back/picker flows. Compose assertions control their own clock. Keep Actions pinned by commit.

Before instrumentation, the bounded emulator readiness gate checks boot completion, package-manager response, unlocked launcher focus and three stable screenshots after display resizing. It preserves observations in `emulator-readiness.json` and fails after 120 seconds. System images use exact revision cache keys; AVD userdata and snapshots are not cached.

CI passes `--normalize-navigation` to recreate API 36's Launcher3 after display overrides. This clears providers cached at the previous density and residual cold-start launcher state. Admission also requires the native three-button navigation inset to equal `48 dp` at the effective density. The reset refuses physical devices, other launchers and gesture navigation. API 26 retains its existing setup.

The reset waits for three identical frames first. Force-stopping Launcher3 during its first post-boot frames panicked the guest `goldfish_sync` driver (#637).

API 26 disables the optional GLDMA transfer path with `-feature -GLDMA`. This targets the `goldfish_pipe_dma_clear_lock` guest-kernel panic observed during repeated display captures (#607). Renderer, memory, capture coverage and assertions remain unchanged. Repeated native verification determines whether this mitigation is sufficient.

Full and focused native jobs stream guest kernel messages into workflow logs and retain the emulator crash database on failure when present. An absent host crash report does not rule out a guest reboot. Preserve the original test logs and host diagnostics when investigating device loss.

Hosted emulator jobs cache the SDK archive by revision and checksum. Cached and downloaded archives must match the [official SDK repository](https://dl.google.com/android/repository/repository2-1.xml)'s size, SHA-1 and package revision before installation. A failed or corrupt download is retried once. Two failures stop setup before device tests.

On disposable GitHub-hosted API 26 emulators, the native runner temporarily disables preinstalled Google Search before instrumentation. Its unrelated JobScheduler crash can obstruct Localica. Cleanup restores its prior enabled state after verifying device and package ownership. Google Search data is preserved. Physical devices are untouched. Localica crashes and all assertions remain fatal.

`ANDROID_DEBUG_KEYSTORE` holds the base64 development key. CI restores it via `LOCALICA_DEBUG_KEYSTORE` and verifies the APK certificate. Back up the key outside Git. Local builds otherwise use the normal debug key. PRs use a disposable key.

Distributed APKs are the non-debuggable, R8-minified `distribution` variant. It keeps the `.debug` application ID and development key for in-place updates, and the artifact keeps the `app-debug.apk` name. CI and releases fail if the manifest is debuggable. Separate production signing is not configured.

Device tests and compatibility run the `debug` variant. R8 keeps all of jaudiotagger because it loads classes by name. The diagnostics export needs no keep rules: its `FileProvider` is manifest-declared and the recorder is referenced directly.

### Capturing a rare freeze without adb

Use any build, including the R8 `distribution` APK. After a freeze, wait at least 2 s so the watchdog records the stuck state, then recover with the Sort/View pill or Back.

Open **More â Settings â About and licenses â Export diagnostics**, tap **Export** and share the text file with the developer. Export before closing the app, because the buffer lives only for the current process. Debug builds also log to `adb logcat -d -s PinchDiagnostics:* CollectionHeaderAnchor:*`.

The `distribution-smoke` job installs the exact published APK on API 26 and 36, waits for the main activity, then runs 600 seeded monkey events. It fails on crashes, ANRs, class-linkage errors or a dead process, and uploads the logcat.

## Release preparation

### Step 1

Update app version/name and increment `versionCode` for every distributed update. Fill the [template](../.github/RELEASE_TEMPLATE.md) into [RELEASE_NOTES](../.github/RELEASE_NOTES.md): basic APK/compatibility/signing introduction, short Changes bullets, **all** resolved issue URLs with brief description fragments, and short Install steps. Include only completed issues. Say so if none. Keep previous published descriptions intact.

### Step 2

Review [previews](#previews), current docs and resolved issues. Commit and push.

For every release, refresh `.github/test-evidence/class-durations.csv` from the preceding release's complete producer run before freezing the candidate. Download its `complete-verification` artifact and copy the generated profile. Record the source run in the commit. Never alter the candidate after its exact-tree gate.

For older producers, download `device-reports-api-*` and run `.github/test-evidence/refresh_durations.py --output .github/test-evidence/class-durations.csv <download-directory>`.

### Step 3

**Wait for a complete green Android run with exactly the release commitâs Git tree before tagging a commit in main.** The producer may be a same-repository integration push or manual full run. Build, all native shards, complete inventory unions, both compatibility jobs and both distribution smoke jobs must succeed. Focused runs cannot qualify.

Main pushes reuse retained exact-tree evidence when available; otherwise they run normal verification. Release selection verifies the original run, attempt, tree, union receipts and artifact identity. APK checksum, version and signing checks remain mandatory. Missing, expired or changed inputs require a new complete run. Unfilled release placeholders block publication.

### Step 4

Verify published assets, close resolved issues/remove workflow labels, and replace completed roadmap outcomes with the next checkpoint.

## Previews

Review README screenshots/GIF for each release. Recapture changed screens from the candidate, inspect at README size, update version/credits and remove obsolete images. Keep navigation recordings short/silent and third-party demo media outside Git/APK.

Reuse `${XDG_DATA_HOME:-$HOME/.local/share}/localica/previews/` for the local library, setup notes and raw captures. Preserve emulator music/app data. Update with `adb install -r` rather than recreating the library.

## Documentation

Use Node.js 22 or newer. From a fresh clone, install only the documentation tooling:

```sh
git clone https://github.com/anastaci1a/localica.git
cd localica
npm ci --prefix tools/docs --ignore-scripts
npm test --prefix tools/docs
npm run check --prefix tools/docs
npm run build --prefix tools/docs
```

Open `build/docs-site/docs/index.html` to review the site locally. No Android SDK, signing key or device is needed for these checks. Mathematical display blocks render to HTML and MathML during the build. Fonts and styles are local assets.

The [documentation workflow](../.github/workflows/documentation.yml) runs these checks for pull requests and pushes to main, and uploads the built site as a workflow artifact. Nothing is published.

[Writing rules](../DOCUMENTATION.md) define the maintained inventory and historical exclusions. Automated checks cover prose limits, local links and fragments, headings, image descriptions and rendered navigation. Review contract meaning and remote evidence separately.
