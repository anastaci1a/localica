# Build and release

## Local verification

Use JDK 17, Android platform 36 and build tools 35.0.0. Set `ANDROID_HOME` or untracked `local.properties` with `sdk.dir=/path/to/sdk`; the pinned wrapper verifies its download checksum.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest  # emulator or connected device
```

Installable development APK: `app/build/outputs/apk/debug/app-debug.apk`. The production release variant is unsigned. Test-only synthetic audio/document-provider fixtures never ship in the app.

Verify affected contracts: migrations/recovery and failed operations; occurrence/cursor/metadata preservation; navigation/search restoration and gesture ownership; playback/lyrics/artwork boundaries; accessibility, short/landscape screens, large fonts and reduced motion. Physical-device review supplements automation. Docs-only edits require content/link review, not Android tests.

Local and CI verification use the same tests. Use focused tests during iteration, then run the complete build/check commands and instrumentation suite on both API 26 and 36 against the final candidate before pushing. Match the emulator configuration below; code, test or build-configuration changes after that gate require another full run. CI repeats verification in a fresh environment on the pushed commit before release.

## Compatibility and dependencies

- Android 8+; MP3 and AAC/M4A are primary formats, with WAV/FLAC/Ogg/Opus using available device decoders. No decoder extensions. Boundary tests check decoded samples, MP3 waveform alignment and transition buffering; sample counts alone are insufficient.
- FLAC tests require a decoder (stock Android supplies one from API 27). Known padding limits: some shortened-final-sample FFmpeg M4A files ([#28](https://github.com/anastaci1a/localica/issues/28)) and the legacy Android 8 MP3 decoder ([#30](https://github.com/anastaci1a/localica/issues/30)). Only the precise known Android 8 failure is skipped after playback checks; other failures remain failures.
- Media3 stays pinned to 1.10.1 pending a stable [MP3 trimming fix](https://github.com/androidx/media/issues/3425); rerun waveform checks before upgrading.
- Jaudiotagger 3.0.1 uses LGPL 2.1+. Bundle its notice/license and include its source archive with releases. To replace it, build the source with Maven, replace `implementation(libs.jaudiotagger)` with the local JAR, and rebuild under your signing key. Localica remains MIT licensed.

## CI and signing

The [Android workflow](../.github/workflows/android.yml) builds main/PR changes and tests API 26/36. API 36 uses explicit `lavapipe`, 720×1280 at 280 dpi, and normal system animations: automatic software/SwiftShader caused rendering crashes; zero-duration system transitions stalled launcher/Back/picker flows. Compose assertions control their own clock. Keep Actions pinned by commit.

`ANDROID_DEBUG_KEYSTORE` holds the base64 development key; CI restores it via `LOCALICA_DEBUG_KEYSTORE` and verifies the APK certificate. Back up the key outside Git. Local builds otherwise use the normal debug key; PRs use a disposable key. Production distribution/signing remains separate.

## Release preparation

1. Update app version/name and increment `versionCode` for every distributed update. Fill the [template](../.github/RELEASE_TEMPLATE.md) into [RELEASE_NOTES](../.github/RELEASE_NOTES.md): basic APK/compatibility/signing introduction, short Changes bullets, **all** resolved issue URLs with brief description fragments, and short Install steps. Include only completed issues; say so if none. Keep previous published descriptions intact.
2. Review [previews](#previews), current docs and resolved issues; commit and push.
3. **Wait for successful build and both device jobs on the exact main SHA before tagging it.** The matching `vX.X.X` workflow reuses that run's APK, verifies version/signature/checksum, and publishes without another build/test matrix. Missing verification/expired artifacts require rerunning main checks; never substitute another commit's APK. Unfilled release placeholders block publication.
4. Verify published assets, close resolved issues/remove workflow labels, and replace completed roadmap outcomes with the next checkpoint.

## Previews

Review README screenshots/GIF for each release. Recapture changed screens from the candidate, inspect at README size, update version/credits and remove obsolete images. Keep navigation recordings short/silent and third-party demo media outside Git/APK.

Reuse `${XDG_DATA_HOME:-$HOME/.local/share}/localica/previews/` for the local library, setup notes and raw captures. Preserve emulator music/app data; update with `adb install -r` rather than recreating the library.
