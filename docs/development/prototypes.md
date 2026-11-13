# Prototype runs

Prototype runs build development APKs and selected tests on API 26/36 for focused proofs, diagnostics and flake repeats. They remain uncertified; only the complete [Android workflow](ci.md) qualifies releases.

- Dispatch `prototype.yml` manually, or call it as a reusable workflow.
- Each run occupies at most two runners.
- The decision manifest records the SHA, the APK identity, the selected coverage and that the complete suites were not run.
- Parallel dispatches need distinct refs; a second dispatch on the same ref cancels the first.
- Status: Implemented.

## What a run contains

| Chain | Jobs |
| --- | --- |
| One | Build, then both preserved-data compatibility checks. |
| Two | The focused device jobs for API 26 and API 36. |

A failed check does not skip later checks in its chain. Build, JVM tests, lint, signature and both compatibility jobs are always required.

Select up to 73 behaviour classes, 4096 characters at most, and describe coverage and limits. Three regression classes are mandatory and count toward that limit: search keyboard, search focus and contrast coverage run on both APIs. Focused jobs time out after 35 minutes.

The build job compiles debug and release Kotlin in one daemon, so it uses the static-checks sizing (`-Xmx6g`, four workers) on the 16 GB hosted runner (#636).

## Runners

The `runner` input defaults to `hosted`. With `runner=self-hosted`, only the two focused emulator jobs move to the owner's locked-down single-job runner, for the owner's manual dispatch on a branch (#592).

- A queued self-hosted job waits for the owner's launcher. It is never rerouted.
- Self-hosted runs allow `repeat` up to 5. Longer repeat proofs stay hosted.
- Build, compatibility and evidence jobs, the Android workflow, release gates and acceptance runs always stay hosted.

During a release, new prototype dispatches wait until the release shards have started.

## Evidence

Prototypes follow the same outcome rules as [CI](ci.md#live-evidence): final XML owns results, at most six live packets are uploaded, a monitoring failure blocks a green result, and a test failure keeps its exit status.

Focused builds warm the UTP host-plugin dependencies before boot, and focused instrumentation resolves dependencies offline. Exhausted network retries keep their attempt logs. The delivery manifest separates infrastructure failures from unclassified ones. Neither permits delivery.

## Recovery from device loss

Recovery never gives a test a second chance. It only finishes work that a lost transport interrupted.

### Teardown went offline

The hosted emulator's adb transport can drop while outputs are pulled after a complete, failure-free run (#675). The runner then waits for the same guest, finishes the interrupted uninstall and reconciles the first attempt's XML. All of these must hold:

- The final XML has every expected method and no failures, and no failure line was observed.
- Gradle failed only on that teardown error. Monitoring is complete.
- The same guest, with an unchanged boot ID, answers adb within three minutes.

The summary records the recovery under `prototype-evidence/teardown-offline/`. Any other failure keeps the original result.

### The emulator died

API 36 jobs sample the emulator's process identity, boot ID, adb state and host memory. One fresh boot is allowed only when a healthy original guest is followed by two terminal loss samples, the failure XML is empty, observer evidence is complete, and there are no product assertions or crashes.

Missing or malformed evidence blocks recovery. A recovered attempt stays visible in the delivery manifest, and cannot authorize another run.

## Limits

No automatic replay or replacement full run follows. Host contracts do not establish live delivery; emulator timings do not establish [device performance](performance.md).
