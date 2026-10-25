# Build and release

## Local verification

Use JDK 17, Android platform 36 and build tools 35.0.0. Set `ANDROID_HOME` or untracked `local.properties` with `sdk.dir=/path/to/sdk`; the pinned wrapper verifies its download checksum.

### Development and final validation

The Localica lead runs effective tests and test setup without separate user approval. Use focused local checks while implementing individual changes. Documentation-only changes need prose, structure, link and rendering checks. Run the complete suite only after all intended changes form a ready final candidate.

Select tests for migrations and failed operations; occurrence/cursor/metadata preservation; navigation restoration and gestures; playback/lyrics/artwork boundaries; accessibility, short/landscape screens, large fonts and reduced motion.

If full validation fails, preserve its first evidence. Repair failures with affected focused tests. Once all repairs are ready, freeze the new candidate and rerun full validation. Investigate every observed failure, including one from a slower parallel run.

One verified complete equivalent pass counts for the exact candidate. A duplicate local and GitHub pass is not routinely required. Changes to code, tests or build inputs require fresh affected checks; final validation applies to the frozen candidate.

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

Select the API 26 or 36 emulator explicitly with `ANDROID_SERIAL`. Match the [emulator configuration](#ci-and-signing). Physical-device review supplements automation. Installable development APK: `app/build/outputs/apk/debug/app-debug.apk`. The production release variant is unsigned. Test-only fixtures never ship in the app.

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

A workflow dispatch may supply `risk_classes` as up to 20 comma-separated fully qualified instrumentation classes (4096 characters maximum). The hosted disposable device job records that selector and runs it before the unchanged complete suite. A failed preflight stops the job and preserves its exit status, log and reports; a passing preflight resets only the app/test packages before running the full suite. Focused success alone is never release evidence. Empty input and ordinary PR/main runs retain full-suite behavior. Preflight isolation is not used by the compatibility gate, whose app data must persist across phases.

### Opt-in prototypes

The opt-in `prototype.yml` lane builds an explicitly uncertified development APK through manual dispatch or an explicit reusable-workflow call. Select affected behavior classes and describe coverage and limitations. Search IME, search-focus and contrast coverage is mandatory on API 26/36; build/JVM/lint/signature and both preserved-data compatibility jobs also remain required. Known issues never waive failures.

The decision manifest records the exact SHA, APK identity, selected coverage and that complete native suites were not run. Prototype results do not certify a release.

The observer preserves command output and provisional failure observations; final instrumentation XML owns test outcomes. A supervised Node 24 publisher uses the pinned artifact SDK to upload at most six bounded live failure packets. Monitoring degradation is explicit and blocks an otherwise green prototype; a test failure retains its original exit status.

No automatic replay or replacement full-suite run is started. Hosted transport evidence must identify the tested publisher revision; offline contract success alone does not prove live delivery. New manual workflows require registration or an explicitly coordinated branch smoke before use.

## Compatibility and dependencies

- Queue schema 7 cannot be reopened by the original schema-6 APK. Before distributing it, dispatch the exact candidate through the Android workflow with `compatibility=true`. Its API 26/36 gate installs candidate → legacy-behavior bridge → candidate with the same package/signature and increasing private version codes, without clearing data. The [isolated overlay](../.github/compatibility/prepare.py) retains current schema, journal and recovery safety; only traversal behavior changes. `ShuffleApkCompatibilityTest` checks duplicate occurrences, saved order/cursor, controller reopening and statistics across `seed`, `bridge`, then `verify`; bridge journal/recovery checks run separately. Private compatibility artifacts are never distributed: use the canonical `localica-debug` APK. Ordinary migration tests do not replace this gate. Track exact-source evidence in [#295](https://github.com/anastaci1a/localica/issues/295).

- Finish tag editing, recovery and pending metadata refreshes before downgrading: older builds cannot read the incremental metadata-refresh records. The existing array journal migrates on upgrade; queue database schemas are independent.
- Android 8+; MP3 and AAC/M4A are primary formats, with WAV/FLAC/Ogg/Opus using available device decoders. No decoder extensions. Boundary tests check decoded samples, MP3 waveform alignment and transition buffering; sample counts alone are insufficient.
- FLAC tests require a decoder; stock Android supplies one from API 27.
- The [AAC edit-list correction](structure.md#aac-edit-list-trimming) covers bounded shortened-sample and rounded-endpoint layouts. Unsupported M4A timing retains native behavior.
- Private-file diagnostics are opt-in. Keep inputs and decoded audio private and untracked. Record observer costs and native output failures separately from sample comparisons; none proves physical speaker continuity.
- Only the measured [Android 8 MP3 padding failure](https://github.com/anastaci1a/localica/issues/30) is skipped after playback checks. Other failures remain fatal.
- Media3 stays pinned to 1.10.1 pending a stable [MP3 trimming fix](https://github.com/androidx/media/issues/3425); rerun waveform checks before upgrading.
- Jaudiotagger 3.0.1 uses LGPL 2.1+. Bundle its notice/license and include its source archive with releases. To replace it, build the source with Maven, replace `implementation(libs.jaudiotagger)` with the local JAR, and rebuild under your signing key. Localica remains MIT licensed.

## CI and signing

The [Android workflow](../.github/workflows/android.yml) builds main/PR changes and tests API 26/36. Match its two virtual CPU cores and 3,072 MiB emulator RAM locally.

API 36 uses explicit `lavapipe`, 720×1280 at 280 dpi, and normal system animations: automatic software/SwiftShader caused rendering crashes; zero-duration system transitions stalled launcher/Back/picker flows. Compose assertions control their own clock. Keep Actions pinned by commit.

On disposable GitHub-hosted API 26 emulators, the native runner temporarily disables preinstalled Google Search before instrumentation. Its unrelated JobScheduler crash can obstruct Localica. Cleanup restores its prior enabled state after verifying device and package ownership. Google Search data is preserved. Physical devices are untouched. Localica crashes and all assertions remain fatal.

`ANDROID_DEBUG_KEYSTORE` holds the base64 development key; CI restores it via `LOCALICA_DEBUG_KEYSTORE` and verifies the APK certificate. Back up the key outside Git. Local builds otherwise use the normal debug key; PRs use a disposable key. Regular GitHub releases retain the existing `.debug` application ID and development key for in-place updates; separate production signing is not configured.

## Release preparation

1. Update app version/name and increment `versionCode` for every distributed update. Fill the [template](../.github/RELEASE_TEMPLATE.md) into [RELEASE_NOTES](../.github/RELEASE_NOTES.md): basic APK/compatibility/signing introduction, short Changes bullets, **all** resolved issue URLs with brief description fragments, and short Install steps. Include only completed issues; say so if none. Keep previous published descriptions intact.
2. Review [previews](#previews), current docs and resolved issues; commit and push.
3. **Wait for successful build, both device jobs, and both compatibility jobs on the exact main SHA before tagging it.** The matching `vX.X.X` workflow reuses that run's APK, verifies version/signature/checksum, and publishes without another build/test matrix. Missing verification/expired artifacts require rerunning main checks; never substitute another commit's APK. Unfilled release placeholders block publication.
4. Verify published assets, close resolved issues/remove workflow labels, and replace completed roadmap outcomes with the next checkpoint.

## Previews

Review README screenshots/GIF for each release. Recapture changed screens from the candidate, inspect at README size, update version/credits and remove obsolete images. Keep navigation recordings short/silent and third-party demo media outside Git/APK.

Reuse `${XDG_DATA_HOME:-$HOME/.local/share}/localica/previews/` for the local library, setup notes and raw captures. Preserve emulator music/app data; update with `adb install -r` rather than recreating the library.
