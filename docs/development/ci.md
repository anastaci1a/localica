# Continuous integration

CI proves one thing: that an exact source tree builds and passes every test exactly once on API 26 and API 36. Evidence is bound to source and APK hashes. Anything missing, extra, duplicated or degraded fails closed. Infrastructure trouble may be retried under strict conditions; a test failure never is.

- Exactly five workflow files exist. One-off runs go through the prototype workflow.
- The device suite runs as eight shards per API. A summary job checks their union against the source inventory.
- Final XML owns test outcomes. Live evidence only preserves early failure observations.
- A monitoring failure fails an otherwise green run.
- Status: Implemented. Budgets: build 10 minutes, slowest shard 22 minutes, whole run 40 minutes.

## Workflows

| File | Purpose |
| --- | --- |
| `android.yml` | Builds application changes on `main` and pull requests, and runs the complete device suite. The only source of release evidence. |
| `prototype.yml` | The opt-in [prototype lane](prototypes.md) for focused, uncertified runs. |
| `documentation.yml` | The [documentation checks](build.md#documentation). Documentation-only changes use this alone. |
| `dependency-cache.yml` | Writes the shared Gradle dependency caches from `main`. |
| `tooling-contracts.yml` | Host contracts for selection, observers and publishers, without a device. |

Never add or rename a workflow file; CI rejects it. Actions are pinned by commit. See the [workflow file rule](../../AGENTS.md#workflow-files).

## Jobs in the Android workflow

Three jobs start together.

| Job | Produces |
| --- | --- |
| `test-apks` | The debug app and instrumentation APKs every shard installs, with metadata, class inventory and the standalone runtime binding. |
| `build` | The R8 delivery APK only: for smoke tests and the release artifact. |
| `static-checks` | Unit tests, lint and the release build. |

Shards wait only for `test-apks`. The summary and verified-run reuse require all three. Every shard verifies source identity and digests, runs with build actions disabled, and checks APK hashes again afterwards. Raw JVM XML is uploaded even when tests fail.

## Shards and the inventory union

Each API suite splits into eight shards on separate emulators with one profile. Whole classes are assigned by measured duration, longest first, from the [duration profile](../../.github/test-evidence/class-durations.csv).

- `device-tests-summary` downloads every shard's XML. It passes only if each API's union equals the source inventory exactly once, skipped assumptions included, and every shard passed.
- Each device artifact keeps `native-shard-plan.json`: hashes, class assignments, expected methods and estimated loads.
- A new class uses the measured median per-method cost until it is profiled. Refresh the profile after adding a slow class.
- Shards time out after 35 minutes. A timed-out shard fails the summary even if its XML is complete.

## Risk preflight

A dispatch may pass `risk_classes`: up to 20 comma-separated instrumentation classes, 4096 characters at most. Each shard runs its slice of those classes first, then its slice of the rest.

- Preflight must run exactly its expected methods, with no failures or skips, before the remainder starts.
- Preflight XML and remainder XML must form the complete disjoint union. Remainder XML alone is not full-suite evidence.
- A failed preflight stops before the remainder and keeps its status, log and reports.
- Original assumptions stay explicit skips, not passes.

## Live evidence

Complete device jobs set `LOCALICA_LIVE_EVIDENCE=true`. An observer and a supervised publisher preserve provisional failure observations while the run is still going.

- A job uploads at most six live packets in total.
- Contradictory or degraded evidence stops the job. Existing evidence is refused, never replayed.
- Test failure and cancellation keep their exit precedence.
- Before failure teardown, read-only crash-buffer and window snapshots are captured.
- Fresh `native-live-evidence` is always uploaded, on failure too.

The publisher tooling uses Node 24 with a committed lockfile, and the Python contracts use a hash-locked [requirements file](../../.github/tests/requirements.txt) for CPython 3.12. Passing contracts validate mocks and protocol handling, not Android outcomes.

## Emulators

| Setting | API 26 | API 36 |
| --- | --- | --- |
| CPU and RAM | Two virtual cores, 3,072 MiB | The same |
| Graphics | `-feature -GLDMA`, against a guest-kernel panic during repeated captures (#607) | Explicit `lavapipe`; automatic software rendering crashed |
| Display | Default | 720×1280 at 280 dpi |
| Animations | Normal | Normal: zero-duration transitions stalled launcher, Back and picker flows |

Before tests, a bounded readiness gate checks boot completion, package-manager response, unlocked launcher focus and three stable screenshots. It records `emulator-readiness.json` and fails after 120 seconds.

API 36 passes `--normalize-navigation` to recreate Launcher3 after display overrides, and requires a 48 dp three-button navigation inset. The reset waits for three identical frames first (#637), and refuses physical devices. On hosted API 26 emulators the runner temporarily disables preinstalled Google Search, whose unrelated crash can obstruct the app, and restores it afterwards.

Jobs stream guest kernel messages into the log and keep the emulator crash database on failure. An absent host crash report does not rule out a guest reboot.

## Caches and retries

| Cache | Rule |
| --- | --- |
| SDK archive, emulator, system images | Keyed by exact revision and checksum, verified against the [official SDK repository](https://dl.google.com/android/repository/repository2-1.xml). Saved only from `main` (#681). AVD userdata and snapshots are not cached. |
| Gradle dependencies | `localica-static-checks` and `localica-test-apks`, restored by compile jobs and saved only from `main`. |

Each compile job records `dependency-downloads=N` and annotates the run when N is above zero. Compile steps run through `gradle-transient-retry.sh`: at most three attempts, and only after an HTTP 5xx, timeout, reset or unreachable network on a download. Failing tests, compile errors and lint findings are never retried. A failed SDK download is retried once.

## Distribution smoke

The `distribution-smoke` job installs the exact published APK on API 26 and 36, waits for the main activity, then runs 600 seeded monkey events. It fails on a crash, ANR, class-linkage error or dead process, and uploads the logcat.

## Contracts

The rules on this page are asserted by the host contracts under `.github/tests` (`test_*.py` and `test_*.mjs`), which the tooling-contracts workflow runs.
