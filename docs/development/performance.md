# Performance

Smoothness is judged on real phones with representative libraries and release builds. Work and memory are bounded by visible or active content. Emulator and debug timings are diagnostic.

- The frame budget at 120 Hz is 8.3 ms. It is never relaxed.
- Timing counts only on plain non-debuggable release-type builds, with screen recording off.
- Performance and stress tests use 13,400 to 50,000 tracks and queues of up to a million entries, and report heap and garbage collection.
- No size-dependent memory and no artificial caps on library size.
- Status: the rules are Implemented as practice. The opt-in suite below is a diagnostic, not acceptance.

## Rules

A35 targets 8.3 ms per whole frame during list flings and player motion. Proposals identify main-thread, render-thread and GPU shares, measured baseline and expected gain. Qualified builds are speed-profile compiled, with aapt2 badging and dexopt receipts.

| Rule | Why |
| --- | --- |
| Operator-scale data | Small fixtures hid an out-of-memory failure that a real library produced. |
| Scale independence | A screen showing twenty rows should cost the same in a library of a thousand songs or fifty thousand. |
| Exact before approximate | Remove duplicate/offscreen work, share exact caches, reduce allocation and use exact cheaper paths first. Prove equality. Then judge approximations using perceptual tolerances, realistic/adversarial fixtures and A35 timing. |
| Qualified builds only | Debuggable and profileable builds change timing. Profileable builds are for diagnosis. |
| None mode as baseline | With glass off, what remains is the cost of the interface itself. See [Glass](../architecture/glass.md#renderers). |

## Budgets in CI

Selection outline benchmarks report geometry and path construction, including caching of unchanged input. On API 26, CI requires a median of at most 1 ms and a 95th percentile of at most 2 ms. API 36 reports timing without enforcing it.

[ScrollPerformanceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ScrollPerformanceTest.kt) only measures scrolling and is [opt-in](testing.md#opt-in-tests).

## The UI frame suite

The opt-in [UI suite](../../app/src/androidTest/java/io/github/anastaci1a/localica/UiPerformanceTest.kt) measures real Window frames with 2,000 seeded tracks and placeholder artwork. It covers list densities, gallery columns, carousels, subviews, Queue, pinch, selection and drag, popups, player, lyrics and search. Its fixture is below operator scale, so it compares builds; it does not accept them.

Install matching debug and test APKs on an owned disposable emulator, then run:

```sh
tools/performance/run-ui-benchmarks.sh emulator-5554 /path/to/evidence FULL_SOURCE_SHA
```

- Run API 26 and 36 separately.
- The script records one warm-up and three repetitions, ranks scenarios by p99, and captures separate traces for the three slowest. Traced passes do not replace baseline samples.
- The suite selects the None renderer and records protocol `stage0-flat-none-v1`. Results from other renderers or older protocols do not transfer.

### Reading results

- Percentiles use nearest rank.
- Jank means a frame's duration exceeds the sampled display refresh period.
- Missed vsyncs are duration-based estimates. Deadline misses are reported separately on API 31+.
- Listener drops, overflow and incomplete delivery are reported, never hidden.
- Received Window timings do not measure presentation latency.

## Frame capture on a device

1. Open **More → Settings → About and licenses**.
2. Start frame capture, repeat the action, and return to stop.
3. Export diagnostics and share the file.

Capture is opt-in, bounded and process-local. It stops after two minutes or when the Activity is destroyed, and a new capture replaces the previous one. No frame listener starts before the user chooses Start. The export holds app and device identity, renderer settings and per-Window summaries. It holds no titles, paths or search text, and excludes system keyboard frames.

Export schema 2 keeps the schema 1 keys and adds a scenario tag, the build source, a capture ID, clock bounds and per-Window identities. Each frame sample has the app VSYNC timestamp, work duration, refresh budget and a nullable deadline, in nanoseconds.

## Startup diagnostics

The [startup controller](../../tools/startup-diagnostics/run.py) runs in the test package, outside the measured process. On an explicitly admitted disposable emulator it seeds a fixture and performs paired cold and warm launches. It records p50, p95 and p99 frame times, accessibility input milestones and optional system traces. Physical-device acceptance remains open.

## Tests

[UiPerformanceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/UiPerformanceTest.kt).

[FrameStatsTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/FrameStatsTest.kt).

[ScrollPerformanceTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/ScrollPerformanceTest.kt).

[StartupDiagnosticsSeedTest](../../app/src/androidTest/java/io/github/anastaci1a/localica/StartupDiagnosticsSeedTest.kt).

## Baseline Profiles and Macrobenchmark

The `:benchmark` module runs outside the app process on API 35 or later, matching the existing shell driver's clock API. Its [shared protocol](../../tools/performance/macrobenchmark/scenarios.json) covers list/gallery flings and slow scrolls, tabs, player expansion, lyrics, search, pinch and process-cold startup. Both profile generation and metrics use the same journeys and existing shell input injectors.

The fixture promotes the existing scale generator: 13,400 synthetic tracks and Queue entries, one tone, no artwork and empty lyrics. It establishes scale and empty-lyrics costs; representative artwork and populated lyrics require separate fixtures.

Dispatch `prototype.yml` with `performance=generate` on a distinct branch. Generation uses API 36 at 720 × 1280, 280 dpi and lavapipe. The `BaselineProfileGenerator` separates startup from later journeys. Commit both generated profiles from `app/src/main/generated/baselineProfiles/`; retain profileinstaller. Generation is explicit and never starts during ordinary assembly.

Generation builds only its non-minified target and producer. Final functional builds retain unit tests, lint and release assemblies.

Use `performance=build` for compilation, unit tests and lint. Functional runs separate the APK producer from one emulator job per shared-protocol scenario, each capped at 40 minutes. Each dispatch needs a distinct branch; delete temporary branches after retaining receipts.

Resume diagnostics with `performance_apk_run` naming an earlier producer run. App, dependency and fixture inputs must match; the test APK is rebuilt once. `performance_scenarios` optionally selects a JSON array of scenario IDs. Empty, duplicate and unknown selections fail before building.

The committed profiles cover startup, list/gallery fling and slow scroll, and tab swipe from hosted run `43686451671`. Player, lyrics, search and pinch profile generation is Incomplete and belongs with the qualified A35 follow-up. All eleven functional scenarios remain required.

That generation spent about five minutes building and 48 minutes collecting before an adapter preparation failure. Full generation sharding remains Incomplete and is deferred with the remaining profiles. The functional producer also builds the no-profile control; unused generation assemblies are excluded. Record actual minutes for each producer and scenario.

Functional end states are verified in an unmeasured first-iteration preflight, then preparation resets the fixture before capture.

Macrobenchmark reports `FrameTimingMetric`, cold-start `StartupTimingMetric` and the existing `Choreographer#doFrame` trace duration. That trace duration is supporting attribution, not Main CPU time. Emulator and profileable results remain diagnostic. Qualified comparisons use the manifest-only plain twin, identical speed-profile warm-up, physical A35 traces, health receipts and no screen recording.

The functional job preserves the full candidate and builds a control from the same source with AGP’s R8 baseline rewriting and startup-layout inputs disabled. Plain packaging then removes the control’s profile assets. Compare the combined baseline-compilation and startup-layout delta on the A35; this does not separate the two contributions.

The job retains R8 metadata for each arm, verifies its DEX checksums against the APK and requires startup layout only in the candidate. This follows Android’s [startup-profile verification](https://developer.android.com/topic/performance/baselineprofiles/confirm-startup-profiles).

### Release ratchet

Status: Implemented tooling. Qualified A35 comparison and initial anchor adoption are pending follow-up. Release-gate activation requires that qualification and review.

Before accepting a release, compare five or more qualified captures per surface with the accepted anchor. Preserve per-capture percentiles; compare their median. Track frame p50/p95, Main CPU milliseconds, allocated megabytes and GC count. Cold startup also tracks initial-display time.

Pack the accepted plain runner’s per-surface `run-*/metrics.json` files with `campaign.mjs pack RUNS IDENTITY_JSON OUTPUT_JSON`. Identity includes `runId`, full `sourceSha` and `apkSha256`; each raw receipt must match that source, APK and fixture. Health, recording and background qualification receipts are required.

Use `anchor` instead of `pack` only when establishing a new, reviewed anchor. Both commands refuse to overwrite an existing output and require every scenario and metric.

The [ratchet checker](../../tools/performance/macrobenchmark/ratchet.mjs) takes an anchor and a campaign JSON file:

```sh
node tools/performance/macrobenchmark/ratchet.mjs check anchor.json campaign.json verdict.json
```

Each anchor records protocol, fixture, device and environment identities plus `bars[surface][metric]` containing `median` and `tolerance`. Initial tolerance is the observed run range. Campaigns carry matching identities, `qualification: "plain"`, `compilation: "speed-profile"`, `screenrecord: false` and `surfaces[surface]` arrays of qualified runs with numeric `metrics`.

A metric above anchor plus tolerance fails. Missing samples, missing metrics, diagnostic builds and incomparable identities also fail. Passing within noise never raises the anchor. Keep the accepted anchor immutable until two independent campaigns confirm an improvement beyond its noise tolerance:

```sh
node tools/performance/macrobenchmark/ratchet.mjs tighten anchor.json first.json second.json proposed-anchor.json
```

Tightening never increases a bar or tolerance. Review the proposed anchor before adopting it. Changing fixtures requires a separately established anchor; it cannot silently replace the old one. The absolute 8.3 ms whole-frame target remains unchanged.
