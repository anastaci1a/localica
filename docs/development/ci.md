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

Eight shards per API assign whole classes by measured longest-first duration from the [profile](../../.github/test-evidence/class-durations.csv). New classes use median per-method cost. Each artifact records hashes, assignments, methods and estimated loads. The summary requires every source method exactly once, including assumption skips, and passing shards. A 35-minute timeout fails even with complete XML.

## Risk preflight

Dispatches may prioritize up to 20 classes within 4096 characters. Each shard runs its slice first; failures/skips stop it. Preflight and remainder XML must form a complete disjoint union. Keep original status, logs and explicit assumption skips.

## Live evidence

Complete jobs enable the observer/supervised publisher, with at most six provisional packets. Final XML owns outcomes; contradictory/degraded monitoring blocks green. Failure/cancellation retains precedence. Read-only crash/window snapshots precede teardown; fresh live evidence uploads on failure too.

Publisher tooling uses locked Node 24 dependencies. Python contracts use hash-locked [CPython 3.12 requirements](../../.github/tests/requirements.txt). Mocks validate protocol handling, not Android outcomes.

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
