# Build and release

## Local verification

Use JDK 17, Android platform 36 and build tools 35.0.0. Set `ANDROID_HOME` or untracked `local.properties` with `sdk.dir=/path/to/sdk`; the pinned wrapper verifies its download checksum.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest  # emulator or connected device
```

Installable development APK: `app/build/outputs/apk/debug/app-debug.apk`. The production release variant is unsigned. Test-only synthetic audio/document-provider fixtures never ship in the app.

Verify affected contracts: migrations/recovery and failed operations; occurrence/cursor/metadata preservation; navigation/search restoration and gesture ownership; playback/lyrics/artwork boundaries; accessibility, short/landscape screens, large fonts and reduced motion. Physical-device review supplements automation. Docs-only edits require content/link review, not Android tests.

Local and CI verification use the same tests and Gradle launcher. Use focused tests during iteration. Before pushing the final candidate, run the complete build/check commands and `connectedDebugAndroidTest` on API 26 and 36 with the emulator configurations below, unless identical source, tests and build inputs already have successful GitHub results. Record and reuse those results; do not repeat the same suites locally as an extra verification step. Changed verification inputs require fresh checks. CI independently verifies pushed release commits in a clean environment under the release gate below.

A workflow dispatch may supply `risk_classes` as up to 20 comma-separated fully qualified instrumentation classes (4096 characters maximum). The hosted disposable device job records that selector and runs it before the unchanged complete suite. A failed preflight stops the job and preserves its exit status, log and reports; a passing preflight resets only the app/test packages before running the full suite. Focused success alone is never release evidence. Empty input and ordinary PR/main runs retain full-suite behavior. Preflight isolation is not used by the compatibility gate, whose app data must persist across phases.

## Compatibility and dependencies

- Queue schema 7 cannot be reopened by the original schema-6 APK. Before distributing it, dispatch the exact candidate through the Android workflow with `compatibility=true`. Its API 26/36 gate installs candidate → legacy-behavior bridge → candidate with the same package/signature and increasing private version codes, without clearing data. The [isolated overlay](../.github/compatibility/prepare.py) retains current schema, journal and recovery safety; only traversal behavior changes. `ShuffleApkCompatibilityTest` checks duplicate occurrences, saved order/cursor, controller reopening and statistics across `seed`, `bridge`, then `verify`; bridge journal/recovery checks run separately. Private compatibility artifacts are never distributed: use the canonical `localica-debug` APK. Ordinary migration tests do not replace this gate. Track exact-source evidence in [#295](https://github.com/anastaci1a/localica/issues/295).

- Finish tag editing, recovery and pending metadata refreshes before downgrading: older builds cannot read the incremental metadata-refresh records. The existing array journal migrates on upgrade; queue database schemas are independent.
- Android 8+; MP3 and AAC/M4A are primary formats, with WAV/FLAC/Ogg/Opus using available device decoders. No decoder extensions. Boundary tests check decoded samples, MP3 waveform alignment and transition buffering; sample counts alone are insufficient.
- FLAC tests require a decoder; stock Android supplies one from API 27.
- The [AAC edit-list correction](structure.md#aac-edit-list-trimming) covers one shortened-final-sample layout. Unsupported M4A timing retains native behavior.
- Only the measured [Android 8 MP3 padding failure](https://github.com/anastaci1a/localica/issues/30) is skipped after playback checks. Other failures remain fatal.
- Media3 stays pinned to 1.10.1 pending a stable [MP3 trimming fix](https://github.com/androidx/media/issues/3425); rerun waveform checks before upgrading.
- Jaudiotagger 3.0.1 uses LGPL 2.1+. Bundle its notice/license and include its source archive with releases. To replace it, build the source with Maven, replace `implementation(libs.jaudiotagger)` with the local JAR, and rebuild under your signing key. Localica remains MIT licensed.

## CI and signing

The [Android workflow](../.github/workflows/android.yml) builds main/PR changes and tests API 26/36. API 36 uses explicit `lavapipe`, 720×1280 at 280 dpi, and normal system animations: automatic software/SwiftShader caused rendering crashes; zero-duration system transitions stalled launcher/Back/picker flows. Compose assertions control their own clock. Keep Actions pinned by commit.

On disposable GitHub-hosted API 26 emulators, the native runner force-stops preinstalled Google Search before testing because its unrelated JobScheduler crash can leave a system dialog above Localica. It does not disable the package, clear its data, or act on local/physical devices. Localica crashes and all native assertions remain fatal.

`ANDROID_DEBUG_KEYSTORE` holds the base64 development key; CI restores it via `LOCALICA_DEBUG_KEYSTORE` and verifies the APK certificate. Back up the key outside Git. Local builds otherwise use the normal debug key; PRs use a disposable key. Regular GitHub releases retain the existing `.debug` application ID and development key for in-place updates; separate production signing is not configured.

## Release preparation

1. Update app version/name and increment `versionCode` for every distributed update. Fill the [template](../.github/RELEASE_TEMPLATE.md) into [RELEASE_NOTES](../.github/RELEASE_NOTES.md): basic APK/compatibility/signing introduction, short Changes bullets, **all** resolved issue URLs with brief description fragments, and short Install steps. Include only completed issues; say so if none. Keep previous published descriptions intact.
2. Review [previews](#previews), current docs and resolved issues; commit and push.
3. **Wait for successful build, both device jobs, and both compatibility jobs on the exact main SHA before tagging it.** The matching `vX.X.X` workflow reuses that run's APK, verifies version/signature/checksum, and publishes without another build/test matrix. Missing verification/expired artifacts require rerunning main checks; never substitute another commit's APK. Unfilled release placeholders block publication.
4. Verify published assets, close resolved issues/remove workflow labels, and replace completed roadmap outcomes with the next checkpoint.

## Previews

Review README screenshots/GIF for each release. Recapture changed screens from the candidate, inspect at README size, update version/credits and remove obsolete images. Keep navigation recordings short/silent and third-party demo media outside Git/APK.

Reuse `${XDG_DATA_HOME:-$HOME/.local/share}/localica/previews/` for the local library, setup notes and raw captures. Preserve emulator music/app data; update with `adb install -r` rather than recreating the library.
