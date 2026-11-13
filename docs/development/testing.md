# Testing

Test in proportion to the change. While implementing, run focused checks that cover what changed. Run the complete suite once, on a frozen final candidate. A failure is evidence to keep and explain, not something to rerun away. Tests assert what the user would see: presented pixels, lifecycles and interruptions, not only state.

- Three layers: JVM tests, device tests on API 26 and 36, and host contracts for the tooling.
- One verified complete pass counts for the exact candidate. Any change needs fresh affected checks.
- Diagnostic and timing tests are opt-in, so default runs stay deterministic.
- Documentation-only changes need only the [documentation checks](build.md#documentation).
- Status: Implemented.

## Layers

| Layer | Where | Covers |
| --- | --- | --- |
| JVM | `app/src/test` | Fast behaviour tests. |
| Device | `app/src/androidTest` | Real Android storage, UI and playback, on API 26 and API 36 emulators. |
| Lint | `lint/src/test` | The UI policy rules and their fixtures. |
| Host contracts | `.github/tests` | Workflow selection, evidence observers and publishers, without a device. |

## Focused and final validation

| Stage | Required evidence |
| --- | --- |
| Development | Focused commands, immutable source, environment, duration and per-test outcomes. |
| Final candidate | Build and checks, complete API 26 and 36 suites, and the [compatibility gate](release.md#compatibility-gate) where it applies. |
| Reused result | The same immutable code, tests, build inputs, API coverage and assertions, with complete passing reports. |
| Failure repair | The original failure evidence, focused repair results, then a new complete candidate result. |

Choose focused tests for what a change can break: migrations, failed operations, occurrence and cursor preservation, navigation restoration, gestures, and playback, lyrics and artwork boundaries. Cover accessibility, short and landscape screens, large fonts and reduced motion.

If full validation fails, keep its first evidence. Repair with focused tests, freeze a new candidate, then rerun full validation. Investigate every observed failure, including one from a slower parallel run. A known issue never waives a failure.

Final commands, run only after the candidate is frozen:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --no-daemon --max-workers=2
./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2
```

## Writing device tests

- Use the [shared Compose test factories](../../app/src/androidTest/java/io/github/anastaci1a/localica/TestComposeRules.kt). They select `StandardTestDispatcher` by default.
- Keep clock advances and input assertions explicit. Compose 1.10 drains due work while idling; do not add another idle-drain.
- Mandatory regressions keep presented-pixel, lifecycle, first-frame, reopened-frame, interruption and disposal assertions.
- Select diagnostic methods explicitly, never with a wildcard exclusion that could remove correctness tests.

### The system keyboard

The system keyboard is device state that survives app resets between classes. A class that shows it through real text entry starts with [`FreshSystemIme`](../../app/src/androidTest/java/io/github/anastaci1a/localica/SystemImeIsolation.kt), which restarts the keyboard process. A test that changes the default keyboard must restart the system keyboard in cleanup, as `FloatingImeLifecycleTest` does (#638).

### Test-package processes

Components that run in the test package's own process cannot rely on the app's classloader. The test APK packages identical bytes of the app's Kotlin standard library, and `NavigationStandaloneRuntimeTest` checks that linkage.

On API 26 and 27 a framework null dereference can kill that process when an accessibility-state change reaches a just-detached window (#730). `TestPackageApplication` survives exactly that exception and rethrows every other. `TestProcessAccessibilityLaunchTest` reproduces it.

## Opt-in tests

| Test or output | Enable with |
| --- | --- |
| `ScrollPerformanceTest` | `scrollPerformance=true`, or the workflow input `scroll_performance`. |
| `StartupDiagnosticsSeedTest` | `startupDiagnostics=disposable-emulator`. It prepares a fixture; it is not performance acceptance. |
| Repeated utility-popup optical recording | `utilityPopupOpticalDiagnostics=true`. |
| Successful SwitchTap frame exports | `switchTapOpticsReview=true`. |
| Local run without the outline timing gate | `selectionOutlineTimingGate=false`. CI leaves it unset. |

Without its flag an opt-in test is skipped by assumption and reported as skipped. Pixel checks and bounded failure evidence stay on. Timing budgets are in [Performance](performance.md).

## Devices

Select an emulator explicitly with `ANDROID_SERIAL`, and match the [CI emulator profile](ci.md#emulators): two virtual CPU cores and 3,072 MiB of RAM.

Physical-device review supplements automation. A shared test phone has one owner at a time: take it, use it, and release it through the project's lease, never by assumption. Emulators cannot establish physical frame rates or speaker continuity.

## UI policy checks

```sh
./gradlew :lint:test :app:lintDebug
bash .github/run-ui-lint.sh app/build/reports/ui-lint
```

The second command fetches `main` once and compares against that immutable commit. The rules, the baseline and the ratchet are explained in [UI system](../architecture/ui-system.md#the-lint-gate).

## Field diagnostics

A rare freeze can be captured on any build, without adb.

1. After a freeze, wait at least 2 seconds so the watchdog records the stuck state.
2. Recover with the Sort/View pill or Back.
3. Open **More → Settings → About and licenses → Export diagnostics**, tap **Export** and share the text file.

Export before closing the app. The trail is an in-memory ring of the last 500 lines (about 256 KB), lost when the process ends.

| The trail holds | Detail |
| --- | --- |
| Stuck-pinch dumps | A pinch transaction, readback or zoom anchor outliving its gesture by 2 seconds, and its recovery. |
| Phase changes | A compact trail of pinch, transaction and readback phases. |
| Nothing, when idle | A healthy idle app records nothing. |

Debuggable builds also mirror each line to logcat:

```sh
adb logcat -d -s PinchDiagnostics:* CollectionHeaderAnchor:*
```

Frame capture for performance work is described in [Performance](performance.md#frame-capture-on-a-device).
