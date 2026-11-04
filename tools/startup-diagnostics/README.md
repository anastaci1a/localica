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

The controller requests Artists when its tab first appears, then drags its loaded gallery. It returns to Albums and flings when that content appears. Requests can land after five seconds on a slow emulator; receipts expose that limitation. Accessibility polling observes shell/content and scroll events. It does not certify presented pixels or instant interaction.

Navigation records tab-selection response separately from content arrival. Artists content requires the outgoing album titles to disappear, so an album's artist subtitle cannot certify destination readiness. Scroll-event timestamps remain accessibility observations, with vertical ranges retained for source review.

Each fifteen-second window retains overlapping `gfxinfo framestats` snapshots, memory and CPU snapshots, input milestones and optional `atrace` output. Analysis also isolates the first five seconds. Frame timestamps use uptime; p50/p95/p99 use nearest rank. Deadline misses are unavailable on older APIs; the explicit 16.7 ms count remains separate.

The controller polls the frame ring every second and deduplicates its overlapping records. This limits startup-frame replacement by later frames. Receipts retain snapshot count, cumulative command duration and failures. Polling adds Binder/observer work, applied identically to cold and warm trials; it is not subtracted from timings.

The frame ring and incomplete tail cannot certify lossless capture. Missing input/content/scroll observations remain explicit rather than passing performance assertions. Timing values never fail a correctness test. Profile attribution requires inspecting raw traces; phase medians do not establish a bottleneck by themselves.

Trace summaries retain Main, RenderThread and background threads present in the final thread snapshot. Threads that already exited may be absent. Scheduler intervals and inclusive trace slices are separate measurements; nested slice durations overlap and must not be added as CPU time.

Check the analyzer independently:

```sh
python3 -m unittest discover -s tools/startup-diagnostics -v
```
