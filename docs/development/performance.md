# Performance

Smoothness is judged against absolute budgets on a real phone, with a release-type build and a library as large as a real one. Work and memory must be bounded by what is visible or active, never by the size of the library. Emulators and debuggable builds find regressions; they do not qualify results.

- The frame budget at 120 Hz is 8.3 ms. It is never relaxed.
- Timing counts only on plain non-debuggable release-type builds, with screen recording off.
- Performance and stress tests use 13,400 to 50,000 tracks and queues of up to a million entries, and report heap and garbage collection.
- No size-dependent memory and no artificial caps on library size.
- Status: the rules are Implemented as practice. The opt-in suite below is a diagnostic, not acceptance.

## Rules

| Rule | Why |
| --- | --- |
| Operator-scale data | Small fixtures hid an out-of-memory failure that a real library produced. |
| Scale independence | A screen showing twenty rows should cost the same in a library of a thousand songs or fifty thousand. |
| Exact before approximate | Try the exact computation first. An approximation needs a perceptual tolerance and a decision. |
| Qualified builds only | Debuggable and profileable builds change timing. Profileable builds are for diagnosis. |
| None mode as baseline | With glass off, what remains is the cost of the interface itself. See [Glass](../architecture/glass.md#renderers). |

## Budgets in CI

Selection outline benchmarks report geometry and path construction, including caching of unchanged input. On API 26, CI requires a median of at most 1 ms and a 95th percentile of at most 2 ms. API 36 reports timing without enforcing it.

`ScrollPerformanceTest` only measures scrolling and is [opt-in](testing.md#opt-in-tests).

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

`UiPerformanceTest`, `FrameStatsTest`, `ScrollPerformanceTest`, `StartupDiagnosticsSeedTest`.
