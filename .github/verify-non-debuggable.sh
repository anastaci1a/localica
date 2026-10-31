#!/usr/bin/env bash
# Fail when the APK can be debugged: usage verify-non-debuggable.sh app.apk
set -euo pipefail
apk=${1:?APK path required}
aapt="$ANDROID_HOME/build-tools/35.0.0/aapt"
"$aapt" dump badging "$apk" > "$apk.badging.txt"
"$aapt" dump xmltree "$apk" AndroidManifest.xml > "$apk.manifest.txt"
if grep -q '^application-debuggable' "$apk.badging.txt" || grep -q 'android:debuggable' "$apk.manifest.txt"; then
  echo "Refusing debuggable APK: $apk" >&2
  exit 1
fi
grep -q "^package: name='io.github.anastaci1a.localica.debug'" "$apk.badging.txt"
echo "Verified non-debuggable: $apk"
rm -f "$apk.badging.txt" "$apk.manifest.txt"
