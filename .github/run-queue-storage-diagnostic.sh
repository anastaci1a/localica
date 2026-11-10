#!/usr/bin/env bash
# Disposable #803 proof only. These observations never certify frame timing or the production actor.
set -euo pipefail
umask 0002
serial=${ANDROID_SERIAL:-emulator-5554}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || exit 2
root=queue-storage-diagnostic
[[ ! -e "$root" ]] || { echo 'Refusing stale queue storage diagnostic evidence' >&2; exit 2; }
mkdir "$root"
target=app/build/outputs/apk/distribution/app-distribution.apk
tests=app/build/outputs/apk/androidTest/distribution/app-distribution-androidTest.apk
tools="$ANDROID_HOME/build-tools/35.0.0"
sha256sum "$target" "$tests" > "$root/apks.sha256"
git rev-parse HEAD > "$root/source-sha.txt"
"$tools/aapt2" dump badging "$target" > "$root/badging.txt"
"$tools/aapt2" dump badging "$tests" > "$root/test-badging.txt"
"$tools/aapt2" dump xmltree "$target" --file AndroidManifest.xml > "$root/manifest.txt"
"$tools/aapt2" dump xmltree "$tests" --file AndroidManifest.xml > "$root/test-manifest.txt"
node .github/queue-storage-inputs.cjs identity "$root"
mapfile -t identity < "$root/identity.txt"
package=${identity[0]}
test_package=${identity[1]}
runner=${identity[2]}
logger=''
cleanup() {
  local result=$? cleanup_result=0
  trap - EXIT
  set +e
  if [[ -n "$logger" ]]; then kill "$logger"; wait "$logger"; fi
  adb -s "$serial" uninstall "$test_package" > "$root/uninstall-tests.txt" 2>&1
  adb -s "$serial" uninstall "$package" > "$root/uninstall-target.txt" 2>&1
  # Missing packages are harmless; still-installed packages contaminate the mandatory gate.
  adb -s "$serial" shell pm list packages "$package" > "$root/packages-after-cleanup.txt" 2>&1
  if [[ "$?" != 0 ]]; then cleanup_result=1; fi
  tr -d '\r' < "$root/packages-after-cleanup.txt" > "$root/packages-after-cleanup-normalized.txt"
  if grep -Fxq "package:$package" "$root/packages-after-cleanup-normalized.txt" || grep -Fxq "package:$test_package" "$root/packages-after-cleanup-normalized.txt"; then cleanup_result=1; fi
  printf 'cleanup_ran=true\noriginal_exit=%s\ncleanup_exit=%s\n' "$result" "$cleanup_result" > "$root/cleanup.txt"
  if (( result == 0 && cleanup_result != 0 )); then result=$cleanup_result; fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
reject() { printf '%s=failed\n' "$1" >> "$root/guards.txt"; exit 1; }
if grep -q 'application-debuggable' "$root/badging.txt"; then reject non_debuggable; fi
printf 'non_debuggable=passed\n' >> "$root/guards.txt"
if grep -q 'E: profileable' "$root/manifest.txt"; then reject plain_manifest; fi
printf 'plain_manifest=passed\n' >> "$root/guards.txt"
adb -s "$serial" shell ps -A > "$root/processes-before.txt"
if grep -wq screenrecord "$root/processes-before.txt"; then reject screenrecord_before; fi
printf 'screenrecord_before=absent\n' >> "$root/guards.txt"
adb -s "$serial" shell getprop > "$root/device-properties.txt"
adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r' > "$root/api.txt"
adb -s "$serial" install -r "$target" > "$root/install-target.txt"
adb -s "$serial" install -r "$tests" > "$root/install-tests.txt"
# A sideloaded APK does not install its packaged profile automatically on these emulators.
adb -s "$serial" shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
  -n "$package/androidx.profileinstaller.ProfileInstallReceiver" > "$root/profile-install.txt"
grep -Eq 'Broadcast completed: result=1([,[:space:]]|$)' "$root/profile-install.txt"
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell cmd package compile -f -m speed-profile "$package" > "$root/compile.txt"
grep -q Success "$root/compile.txt"
adb -s "$serial" shell dumpsys package dexopt > "$root/dexopt.txt"
adb -s "$serial" shell dumpsys package "$package" > "$root/target-dexopt.txt"
node .github/queue-storage-inputs.cjs dexopt "$root"
# Keep a continuous stream. Markers establish readiness and drain without clearing a finite buffer.
marker="queue-storage-${GITHUB_RUN_ID:-local}-${TEST_API:-unknown}-$(date +%s)"
adb -s "$serial" logcat -v threadtime > "$root/logcat.txt" 2> "$root/logcat-stderr.txt" &
logger=$!
wait_marker() {
  local i
  adb -s "$serial" shell log -p i -t QueueStorageDiagnostic "$1"
  for i in {1..100}; do
    if grep -Fq "$1" "$root/logcat.txt"; then return 0; fi
    kill -0 "$logger" || return 1
    sleep 0.1
  done
  return 1
}
wait_marker "$marker-ready"
printf '%s\n' "$marker-ready" > "$root/ready-marker.txt"
set +e
timeout 900 adb -s "$serial" shell am instrument -w -r \
  -e queueOrderBlobProbe true \
  -e class io.github.anastaci1a.localica.QueueSequenceTest,io.github.anastaci1a.localica.QueueTraversalTest,io.github.anastaci1a.localica.QueueOrderBlobProbeTest \
  "$test_package/$runner" > "$root/instrumentation.txt" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$root/adb-exit.txt"
wait_marker "$marker-drained"
printf '%s\n' "$marker-drained" > "$root/drain-marker.txt"
kill "$logger"
wait "$logger" || true
logger=''
adb -s "$serial" shell ps -A > "$root/processes-after.txt"
if grep -wq screenrecord "$root/processes-after.txt"; then reject screenrecord_after; fi
printf 'screenrecord_after=absent\n' >> "$root/guards.txt"
[[ "$status" == 0 ]]
# am instrument may exit zero after a JUnit failure: require all expected methods explicitly.
node .github/reconcile-queue-storage-diagnostic.cjs "$root"
