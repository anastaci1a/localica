# Build and verification

Use JDK 17 and an Android SDK with platform 36 and build tools 35.0.0. Set `ANDROID_HOME` or an untracked `local.properties` containing `sdk.dir=/path/to/sdk`. The checked-in wrapper downloads the pinned Gradle version and verifies its checksum.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest  # running emulator or connected device
```

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`. The release variant is intentionally unsigned until production distribution is configured. `androidTest` contains original synthetic audio and a test-only document provider for real folder-picker and codec tests; neither is included in the app. Boundary tests compare decoded samples, MP3 waveform alignment, and buffering across transitions. The FLAC case runs only when a device exposes a FLAC decoder (stock Android supplies it from API 27). The precise Android 8 MP3 padding failure is reported as a known-failure skip after checking playback/transitions; see [#30](https://github.com/anastaci1a/localica/issues/30). Other MP3 sample counts or decoder failures still fail the test.

Media3 is pinned to 1.10.1 until a stable release includes the fix for its [MP3 trimming regression](https://github.com/androidx/media/issues/3425). Boundary length alone cannot detect that regression; the waveform checks must pass before upgrading.

Jaudiotagger 3.0.1 supplies tag writing under LGPL 2.1 or later. Its notice/license are bundled in the app's open-source licenses screen; releases include the unmodified library's source archive. To use a modified version, build that source with Maven, replace `implementation(libs.jaudiotagger)` with a local JAR dependency, and rebuild using the commands above. A rebuilt APK uses your own development signing key. Localica's own code remains MIT licensed.

## Automation and versions

The Android workflow checks each main push and pull request, uploads a development APK, and runs device tests on API 26 and 36. API 36 uses explicit `lavapipe` graphics and a 720×1280 display at 280 dpi, preserving the Pixel 2's logical dimensions while reducing rendering work. Both `swiftshader_indirect` and the automatically selected `software` backend intermittently crashed the emulator during lyric rendering. Explicit Mesa rendering completed the full local suite; keep its default feature selection. Keep system animation scales at their normal values: forcing them to zero caused launcher/Back and document-picker stalls on the cold CI emulator. Compose animation assertions use their own controlled clock. Actions are pinned to commit hashes.

Wait for successful main verification before tagging its exact commit. A `vX.X.X` tag must match `versionName` in `app/build.gradle.kts`; increase `versionCode` for each new build distributed as an update. The tag workflow requires successful build and both device jobs from a main push for that commit, downloads its existing APK, checks the version and signing identity, and publishes it with a checksum. It does not rebuild or repeat the test matrix. Missing verification or an expired artifact blocks publishing: rerun the main checks, then rerun the release job. Never substitute an APK from another commit.

The repository secret `ANDROID_DEBUG_KEYSTORE` holds the base64-encoded development keystore so successive CI APKs can update one another. CI passes its restored path through `LOCALICA_DEBUG_KEYSTORE` and verifies the APK certificate against that keystore before upload. Keep a backup outside Git. Local builds use the usual debug key unless this variable is set; pull requests use a disposable debug key. Production signing requires a separate key and distribution decision.

### Release descriptions

Before pushing a release tag, copy the [template](../.github/RELEASE_TEMPLATE.md) over [the current release notes](../.github/RELEASE_NOTES.md), fill every placeholder, and commit and push the result with the version change. Review issues resolved since the preceding release so the linked list is complete; references in commits alone do not mean an issue is resolved. Keep the change bullets and installation steps short, following the [release writing rules](../agents/README.md#versioning-and-releases).

The workflow checks the version marker and rejects unfilled placeholders, then publishes this file as the release body. Replace the file for each release; Git and GitHub Releases retain previous descriptions. This format starts with v0.5.0; earlier release descriptions stay as published.
