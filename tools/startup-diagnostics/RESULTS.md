# Startup diagnostics: 2026-11-04

Issue [#406](https://github.com/anastaci1a/localica/issues/406) remains incomplete. These emulator trials locate broad Main drawing and scheduling pressure. A specific production correction has not been isolated. Physical-device acceptance remains open.

## Source and workload

- APK/controller source: [ffa5be45](https://github.com/anastaci1a/localica/tree/ffa5be45d57bc2b6a73739873090d372ed220235). Application code remains the assigned integration base, c6d74a09.
- Analyzer source: [295153f0](https://github.com/anastaci1a/localica/tree/295153f09c079ae908a10419cb955bb77a87fa34). The [per-trial CSV](results-20261104.csv) retains every repetition.
- API 36 AOSP x86_64, emulator 37.2.12, Lavapipe, two virtual CPU cores, 3072 MiB RAM; container quota two CPUs.
- Debug APK; 720 × 1280 pixels, 280 dpi, normal animations; five columns, 13,313 metadata records, 1,110 album sources and 278 artists.
- Five independently reseeded cold/warm pairs per artwork condition. Condition order alternates between repetitions. Each window lasts fifteen seconds.

Process-cold means force-stop with no target PID before launch. Filesystem caches remain warm. Process-warm recreates the activity in the same verified process. The interval between windows ranges from 12.8 to 16.7 seconds.

Tracks in one album share a file. Embedded mode supplies deterministic 1024-pixel JPEGs. Private file access and unavailable folder grants differ from a normal SAF library. Missing-art mode includes fallback drawing and metadata work; it does not isolate decoder cost.

## Frame measurements

Values are milliseconds. Each cell reports the median of five per-trial nearest-rank percentiles. The p95 range retains the smallest and largest trial. Deadline counts cover valid retained rows, without pooling percentile distributions.

| Condition | p50 | p95 (range) | p99 | Deadline misses / retained frames |
| --- | ---: | ---: | ---: | ---: |
| Missing art / cold | 187 | 352 (266–549) | 1071 | 493 / 567 |
| Missing art / warm | 194 | 331 (313–873) | 2536 | 543 / 623 |
| Embedded JPEG / cold | 190 | 284 (261–513) | 1382 | 525 / 595 |
| Embedded JPEG / warm | 187 | 290 (282–312) | 2135 | 642 / 722 |

First-five-second p95 medians are 3,081/2,621 ms for missing-art cold/warm and 2,975/2,598 ms for embedded cold/warm. Individual first-five-second samples contain only 1–27 valid frames. These small samples limit percentile interpretation.

Across twenty windows, 2,507 valid frames remain. Sixteen unique traversals lack a valid completion timestamp at the last snapshot, with 0–3 unresolved per window. Future-dated completions are rejected using each dump’s observation clock; later valid observations recover the same traversal. Real long frames remain in the CSV.

All twenty trace-buffer headers report retained entries equal to written entries. Frame-ring loss and incomplete tails remain unverified. Snapshot commands consume 5.3–8.8 seconds of cumulative wall time per window, including waits. Their observer overhead is retained and not subtracted.

## Input milestones

The table reports median per-action event-to-Main queue p95, in milliseconds. Matching uses target Main touch-delivery records and injected event timestamps. This includes dispatch and Main scheduling, while tab selection, scroll motion, content arrival and presented pixels remain unverified.

| Condition | OS first draw | Artists tap | Albums tap | Drag | Fling |
| --- | ---: | ---: | ---: | ---: | ---: |
| Missing art / cold | 3447 | 1291 | 115 | 215 | 101 |
| Missing art / warm | 1901 | 1111 | 81 | 105 | 106 |
| Embedded JPEG / cold | 3304 | 1158 | 90 | 321 | 99 |
| Embedded JPEG / warm | 2076 | 1168 | 82 | 104 | 102 |

All eighty action windows contain matched Main deliveries. Artists taps match two events each, so their nearest-rank p95 is the slower event. Nineteen of twenty first requests occur within five seconds; the remaining request occurs at 5,115 ms. Planned times and actual delays remain in raw receipts.

Compose scroll and selection callbacks were absent with UiAutomation alone. The compiled Compose 1.10.6 delegate gates events on enabled accessibility services; the emulator had none. [AndroidX source](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat.android.kt) explains UiAutomator’s exclusion. No accessibility service was enabled to change this workload.

## Attribution and remaining work

Values below are median seconds per fifteen-second window. Running and runnable intervals are scheduler measurements. Draw and recomposition columns are inclusive wall-time slices; nested slices overlap and cannot be added as CPU time.

| Condition | Main running | Main runnable | RenderThread running | Record View draw | Compose recompose |
| --- | ---: | ---: | ---: | ---: | ---: |
| Missing art / cold | 9.1 | 4.4 | 1.6 | 6.7 | 2.6 |
| Missing art / warm | 9.4 | 4.3 | 1.5 | 7.7 | 2.6 |
| Embedded JPEG / cold | 9.3 | 4.3 | 1.5 | 6.6 | 2.6 |
| Embedded JPEG / warm | 9.3 | 4.4 | 1.8 | 7.6 | 2.3 |

Main drawing and runnable pressure persist in both artwork conditions and process-warm launches. The trace supports this broad attribution, while a specific query, decode, upload or allocation operation remains unisolated. No production optimization is selected from these data.

Warm loading was not awaited to completion. Eight valid post-pair probes found only 50–98 persisted artwork-color records. Normal settled scrolling needs separate measurement. Host load samples range from 2.36 to 17.20 on sixteen logical CPUs; the larger tails remain included.

Physical acceptance still requires representative hardware, realistic artwork and library access, independently verified navigation/scroll presentation, settled scrolling and density controls. Allocation, GC, decoder/upload attribution and profiler overhead also remain open. Emulator throughput supplies no device-level responsiveness acceptance.

## Verification

- Android test APK build passed. Both existing StartupFrameMetricsTest methods passed on API 36 against the measured test APK.
- Twenty explicit seed invocations passed, exercising one unique JUnit method. Diagnostic windows are instrumentation captures, not additional JUnit methods.
- Twelve analyzer tests and five documentation-tool tests passed. Full API 26/36 android.yml acceptance remains pending.
