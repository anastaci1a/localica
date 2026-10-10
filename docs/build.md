# Build and verification

Use JDK 17 and an Android SDK with platform 36 and build tools 35.0.0. Set `ANDROID_HOME` or an untracked `local.properties` containing `sdk.dir=/path/to/sdk`. The checked-in wrapper downloads the pinned Gradle version and verifies its checksum.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest  # running emulator or connected device
```

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`. The release variant is intentionally unsigned until production distribution is configured. `androidTest` contains original synthetic audio and a test-only document provider for real folder-picker and codec tests; neither is included in the app.

## Automation and versions

The Android workflow checks each main push and pull request, uploads a development APK, and runs device tests on API 26 and 36. Actions are pinned to commit hashes. A `vX.X.X` tag must match `versionName` in `app/build.gradle.kts`; increase `versionCode` for each new build distributed as an update. Once all tag checks pass, the workflow publishes a development prerelease with an APK and checksum.

The repository secret `ANDROID_DEBUG_KEYSTORE` holds the base64-encoded development keystore so successive CI APKs can update one another. CI passes its restored path through `LOCALICA_DEBUG_KEYSTORE` and verifies the APK certificate against that keystore before upload. Keep a backup outside Git. Local builds use the usual debug key unless this variable is set; pull requests use a disposable debug key. Production signing requires a separate key and distribution decision.

### Release descriptions

Before pushing a release tag, copy the [template](../.github/RELEASE_TEMPLATE.md) over [the current release notes](../.github/RELEASE_NOTES.md), fill every placeholder, and commit and push the result with the version change. Review issues resolved since the preceding release so the linked list is complete; references in commits alone do not mean an issue is resolved. Keep the summary and installation steps short, following the [release writing rules](../agents/README.md#versioning-and-releases).

The workflow checks the version marker and rejects unfilled placeholders, then publishes this file as the release body. Replace the file for each release; Git and GitHub Releases retain previous descriptions. This format starts with v0.5.0; earlier release descriptions stay as published.
