# Startup diagnostics

Measure a debuggable APK on an owned disposable emulator. The controller never runs through the normal JUnit suite; the seed test skips unless explicitly selected with its opt-in argument. These tools clear the target app's data. Keep output outside the repository.

Build and install the debug app and test APK, then run with Python 3 and `adb` on PATH:

```sh
./gradlew assembleDebug assembleDebugAndroidTest --no-daemon --max-workers=2
adb -s emulator-5584 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5584 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/startup-diagnostics/run.py --serial emulator-5584 \
  --admit-disposable-emulator --pairs 5 --artwork embedded --columns 5 \
  --output /path/outside/repository/embedded-five-columns
```

Use `--artwork none` for the missing-art control, `--columns 3` for density comparison, and `--trace` for matched diagnostic trials. Trace collection adds overhead; keep traced and untraced samples separate. Never pool frames across repetitions or compare absolute emulator timings to physical-device performance.

The seed creates 13,313 metadata rows and 1,110 album sources from the original test tone. Embedded mode writes deterministic 1024-pixel JPEG covers. Tracks in one album share its source. This controlled synthetic distribution does not represent a personal library or realistic artwork diversity. Scanning stays disabled.

The controller instruments the test package with a distinct UID. Cold means the target has no process after `am force-stop`; filesystem caches remain warm. Warm means a fresh task/activity in the same verified process, after capture teardown and at least three seconds of settling. Every pair retains PID evidence and the observed interval between windows.

Launch output retains Android's `am start -W` timing and launch state. Frame snapshots begin before launch; input starts after launch completion so the warm control cannot target its outgoing activity. Shell commands retain stderr and use explicit shell interpretation.

Default replay discovers navigation coordinates outside measurement, then clears and reseeds the app again. It schedules Artists at two seconds, a drag at 4.5 seconds, Albums at eight seconds, and a fling at 10.5 seconds. Launch completion or scheduling can delay requests; receipts retain planned and actual times.

Replay performs no hierarchy reads inside either window. Vertical accessibility events record gesture responses and ranges. Navigation selection and content-readiness latency remain unmeasured. A scroll event cannot prove which page supplied it. Keep this input coverage separate from readiness and presented-pixel acceptance.

Use `--input-mode adaptive` to investigate accessibility-observed selection and content. It requests Artists when the tab appears and scrolls after content arrives. Artists requires outgoing album titles to disappear. Tree reads can dominate the window on slow emulators; keep these samples separate from replay.

Each fifteen-second window retains overlapping `gfxinfo framestats` snapshots, memory and CPU snapshots, input milestones and optional `atrace` output. Analysis also isolates the first five seconds. Frame timestamps use uptime; p50/p95/p99 use nearest rank. Deadline misses are unavailable on older APIs; the explicit 16.7 ms count remains separate.

The controller polls the frame ring every second and deduplicates its overlapping records. This limits startup-frame replacement by later frames. Receipts retain snapshot count, cumulative command duration and failures. Polling adds Binder/observer work, applied identically to cold and warm trials; it is not subtracted from timings.

Each dump retains its observation clock. Completion timestamps later than that observation are invalid; raw values remain archived. Later valid observations can recover the same traversal. Reports distinguish recovered and unresolved frames. Unresolved completions limit total-duration percentiles; CPU and trace measurements remain separate.

Discovery and adaptive polling request descendant prefetch on API 33 and newer. Long scrollable nodes sample two children. Receipts retain traversal time and node counts. Replay uses the lowest matching navigation label to avoid page headers. No new input sequence starts after the nominal window ends.

The frame ring and incomplete tail cannot certify lossless capture. Missing input/content/scroll observations remain explicit rather than passing performance assertions. Timing values never fail a correctness test. Profile attribution requires inspecting raw traces; phase medians do not establish a bottleneck by themselves.

Trace summaries retain Main, RenderThread and background threads present in the final thread snapshot. Threads that already exited may be absent. Scheduler intervals and inclusive trace slices are separate measurements; nested slice durations overlap and must not be added as CPU time.

Check the analyzer independently:

```sh
python3 -m unittest discover -s tools/startup-diagnostics -v
```
