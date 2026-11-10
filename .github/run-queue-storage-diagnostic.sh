#!/usr/bin/env bash
# Disposable #803 proof only. These observations never certify frame timing or the production actor.
set -euo pipefail
umask 0002
serial=${ANDROID_SERIAL:-emulator-5554}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || exit 2
root=queue-storage-diagnostic
[[ ! -e "$root" ]] || { echo 'Refusing stale queue storage diagnostic evidence' >&2; exit 2; }
mkdir "$root"
package=io.github.anastaci1a.localica.debug
target=app/build/outputs/apk/distribution/app-distribution.apk
tests=app/build/outputs/apk/androidTest/distribution/app-distribution-androidTest.apk
tools="$ANDROID_HOME/build-tools/35.0.0"
sha256sum "$target" "$tests" > "$root/apks.sha256"
git rev-parse HEAD > "$root/source-sha.txt"
"$tools/aapt2" dump badging "$target" > "$root/badging.txt"
! grep -q 'application-debuggable' "$root/badging.txt"
"$tools/aapt2" dump xmltree "$target" --file AndroidManifest.xml > "$root/manifest.txt"
! grep -q 'E: profileable' "$root/manifest.txt"
adb -s "$serial" shell ps -A > "$root/processes-before.txt"
! grep -wq screenrecord "$root/processes-before.txt"
adb -s "$serial" shell getprop > "$root/device-properties.txt"
adb -s "$serial" install -r "$target" > "$root/install-target.txt"
adb -s "$serial" install -r "$tests" > "$root/install-tests.txt"
adb -s "$serial" shell cmd package compile -f -m speed-profile "$package" > "$root/compile.txt"
grep -q Success "$root/compile.txt"
adb -s "$serial" shell dumpsys package dexopt > "$root/dexopt.txt"
grep -F -A 8 "[$package]" "$root/dexopt.txt" > "$root/target-dexopt.txt"
grep -q 'status=speed-profile' "$root/target-dexopt.txt"
adb -s "$serial" logcat -c
set +e
timeout 900 adb -s "$serial" shell am instrument -w -r \
  -e queueOrderBlobProbe true \
  -e class io.github.anastaci1a.localica.QueueSequenceTest,io.github.anastaci1a.localica.QueueTraversalTest,io.github.anastaci1a.localica.QueueOrderBlobProbeTest \
  "$package.test/io.github.anastaci1a.localica.NavigationRandomOwnedSession" > "$root/instrumentation.txt" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$root/adb-exit.txt"
adb -s "$serial" logcat -d -v threadtime > "$root/logcat.txt"
adb -s "$serial" shell ps -A > "$root/processes-after.txt"
! grep -wq screenrecord "$root/processes-after.txt"
# The debug mandatory gate installs its own same-source inputs after this diagnostic.
adb -s "$serial" uninstall "$package.test" > "$root/uninstall-tests.txt"
adb -s "$serial" uninstall "$package" > "$root/uninstall-target.txt"
[[ "$status" == 0 ]]
# am instrument may exit zero after a JUnit failure: require all expected methods explicitly.
node .github/reconcile-queue-storage-diagnostic.cjs "$root"
