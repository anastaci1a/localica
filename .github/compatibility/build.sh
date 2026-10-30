#!/usr/bin/env bash
set -euo pipefail
root="$RUNNER_TEMP/shuffle-compatibility"
tools="$ANDROID_HOME/build-tools/35.0.0"
runner=$(python3 "$GITHUB_WORKSPACE/.github/compatibility/instrumentation_runner.py" "$root/reports/provenance.json")
expected=$(keytool -exportcert -alias androiddebugkey -keystore "$LOCALICA_DEBUG_KEYSTORE" -storepass android | sha256sum | cut -d ' ' -f 1)
for phase in seed bridge verify; do
  tree="$root/$phase"
  mkdir -p "$root/apks/$phase"
  (cd "$tree" && ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon --max-workers=2) 2>&1 | tee "$root/reports/$phase-build.log"
  cp "$tree/app/build/outputs/apk/debug/app-debug.apk" "$root/apks/$phase/compatibility-$phase-app.apk"
  cp "$tree/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$root/apks/$phase/compatibility-$phase-test.apk"
  code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["variants"][sys.argv[2]]["version_code"])' "$root/reports/provenance.json" "$phase")
  for kind in app test; do
    apk="$root/apks/$phase/compatibility-$phase-$kind.apk"
    "$tools/aapt" dump badging "$apk" > "$root/reports/$phase-$kind-badging.txt"
    "$tools/apksigner" verify --print-certs "$apk" > "$root/reports/$phase-$kind-signing.txt"
    actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$root/reports/$phase-$kind-signing.txt")
    test "$expected" = "$actual"
    "$tools/aapt" dump xmltree "$apk" AndroidManifest.xml > "$root/reports/$phase-$kind-manifest.txt"
    python3 "$GITHUB_WORKSPACE/.github/compatibility/check-apk.py" "$root/reports/$phase-$kind-badging.txt" "$kind" "$code" "$root/reports/$phase-$kind-manifest.txt" "$runner"
    (cd "$root/apks/$phase" && sha256sum "$(basename "$apk")" > "$(basename "$apk").sha256")
  done
  # Sequential builds keep three independent Gradle output trees off the constrained runner.
  rm -rf "$tree"
done
