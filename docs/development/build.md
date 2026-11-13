# Build

Use the pinned Gradle wrapper, JDK 17 and Android SDK. Debug serves tests; distribution produces non-debuggable R8-minified installs; release is unsigned. Stable application ID and signing allow in-place updates.

- Android 8 or newer. MP3 and AAC/M4A are the primary formats.
- A build without a Last.fm key compiles and disables syncing.
- Documentation has its own small Node toolchain and needs no Android SDK.
- Status: Implemented. Separate production signing is not configured.

## Toolchain

Use JDK 17, Android platform 36 and build tools 35.0.0. Set `ANDROID_HOME`, or an untracked `local.properties` with the SDK path. The wrapper verifies its download checksum.

Register the merge driver for `.github/prototype-risk-classes.txt` once per clone. It merges both sides' classes and drops duplicate lines, which the risk inventory contract rejects.

```sh
git config merge.union-dedupe.driver 'sh tools/git/merge-union-dedupe.sh %O %A %B'
```

For repeated compile or assemble runs, the configuration cache is an opt-in:

```sh
./gradlew assembleDebug --configuration-cache --configuration-cache-problems=fail --no-daemon --max-workers=2
```

Run selected tests separately, and verify any other task graph before adopting the cache.

## Variants and signing

| Variant | Use | Notes |
| --- | --- | --- |
| `debug` | Device tests and compatibility. Output: `app/build/outputs/apk/debug/app-debug.apk`. | Debuggable. |
| `distribution` | Every distributed APK. | Non-debuggable, R8-minified, profileable for explicit Perfetto capture. Keeps the `.debug` application ID and development key. |
| `release` | Build check only. | Unsigned. |

CI and releases fail if the distributed manifest is debuggable (`verify-non-debuggable.sh`). The artifact keeps the `app-debug.apk` name.

`ANDROID_DEBUG_KEYSTORE` holds the base64 development key. CI restores it through `LOCALICA_DEBUG_KEYSTORE` and verifies the APK certificate. Back the key up outside Git. Local builds otherwise use the normal debug key, and pull requests use a disposable key. Test-only fixtures never ship in the app.

### R8

- R8 keeps all of Jaudiotagger, because it loads classes by name.
- The rules keep Compose 1.10's `AndroidLayoutApi34` helper as an optimization boundary, so its guarded adapter stays out of classes loaded on API 26. Recheck when upgrading Compose or R8.
- The diagnostics export needs no keep rules.

The distribution smoke job in [CI](ci.md#distribution-smoke) guards class linkage on the real APK.

## Source identity

Builds record the checkout's full Git SHA. For a source archive without Git metadata, pass the SHA from its verified provenance:

```sh
./gradlew assembleDebug -PlocalicaSourceSha=FULL_SOURCE_SHA
```

## Formats and dependencies

| Topic | Rule |
| --- | --- |
| Formats | MP3 and AAC/M4A are primary. WAV, FLAC, Ogg and Opus use available device decoders. No decoder extensions. |
| FLAC | Stock Android supplies a decoder from API 27. FLAC tests require one. |
| Media3 | Pinned to 1.10.1 pending a stable [MP3 trimming fix](https://github.com/androidx/media/issues/3425). Rerun waveform checks before upgrading. |
| Jaudiotagger | 3.0.1, LGPL 2.1+. Its notice and licence are bundled, and its source archive ships with releases. |
| Licence | Localica is MIT licensed. |

To replace Jaudiotagger, build its source with Maven, replace `implementation(libs.jaudiotagger)` with the local JAR, and rebuild under your own signing key.

Boundary corrections for MP3 and AAC are in [Audio corrections](../architecture/audio-corrections.md). Private-file audio diagnostics are opt-in; keep their inputs and decoded audio private and untracked.

## Last.fm build key

APK-producing Gradle steps receive the optional Actions secret `LASTFM_API_KEY`. Gradle also accepts the `lastfmApiKey` project property. Both populate `BuildConfig.LASTFM_API_KEY`.

- A missing key compiles, with syncing disabled. Fork pull requests need no secret.
- The key is bundled into the client APK for read-only API calls. No Last.fm shared secret is used.
- Diagnostics, evidence publishers and release upload steps never receive the key.
- Tooling contracts enforce the secret's scope and stop workflow commands printing it. Tests make no live API requests.

## Documentation

Use Node.js 22 or newer. From a fresh clone, install only the documentation tooling:

```sh
git clone https://github.com/anastaci1a/localica.git
cd localica
npm ci --prefix tools/docs --ignore-scripts
npm test --prefix tools/docs
npm run check --prefix tools/docs
npm run build --prefix tools/docs
```

Open `build/docs-site/docs/index.html` to review the site locally. Mathematical blocks render to HTML and MathML during the build. Fonts and styles are local assets.

The [documentation workflow](../../.github/workflows/documentation.yml) runs these checks for pull requests and pushes to `main`, and uploads the built site as a workflow artifact. Nothing is published to a website.

The checks cover prose limits, page length, the opening overview, links and fragments, headings, image descriptions and rendered navigation. The [writing standard](../../DOCUMENTATION.md) defines them. Review meaning and remote evidence separately.
