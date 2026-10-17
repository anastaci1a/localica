#!/usr/bin/env bash
set -euo pipefail
root="$RUNNER_TEMP/shuffle-compatibility"
reports="$root/reports"
package=io.github.anastaci1a.localica.debug
scripts="$GITHUB_WORKSPACE/.github/compatibility"
# Run inside the emulator action: evidence is captured before its shutdown hook.
diagnostics() {
  status=$?
  trap - EXIT
  if (( status != 0 )); then
    set +e
    mkdir -p "$reports/failure"
    timeout --kill-after=5s 20s adb logcat -d > "$reports/failure/logcat.txt" 2>&1 || echo '::warning::Compatibility logcat capture failed'
    timeout --kill-after=5s 20s adb shell dumpsys package "$package" > "$reports/failure/package.txt" 2>&1 || echo '::warning::Compatibility package capture failed'
    # Explicit synthetic allowlist only; never copy the app databases directory wholesale.
    for file in files/shuffle-apk-compat-token databases/shuffle-apk-compat-queues.db databases/shuffle-apk-compat-queues.db-wal databases/shuffle-apk-compat-queues.db-shm databases/shuffle-apk-compat-statistics.db databases/shuffle-apk-compat-statistics.db-wal databases/shuffle-apk-compat-statistics.db-shm; do
      timeout --kill-after=5s 20s adb exec-out run-as "$package" cat "$file" > "$reports/failure/${file//\//_}" 2> "$reports/failure/${file//\//_}.stderr" || echo "::warning::Synthetic compatibility fixture unavailable: $file"
    done
  fi
  exit "$status"
}
trap diagnostics EXIT
instrument() {
  name=$1; selector=$2; count=$3
  shift 3
  # A hung instrumentation must leave time for EXIT diagnostics before the job deadline.
  timeout --kill-after=30s 10m adb shell am instrument -w -r -e class "$selector" "$@" "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$reports/$name-instrumentation.txt" 2>&1
  cat "$reports/$name-instrumentation.txt"
  python3 "$scripts/check-instrumentation.py" "$reports/$name-instrumentation.txt" "$count"
}
for phase in seed bridge verify; do
  for kind in app test; do
    (cd "$root/apks/$phase" && sha256sum --check "compatibility-$phase-$kind.apk.sha256")
    adb install -r "$root/apks/$phase/compatibility-$phase-$kind.apk" | tee "$reports/$phase-$kind-install.txt"
    grep -qx 'Success' "$reports/$phase-$kind-install.txt"
  done
  adb shell dumpsys package "$package" > "$reports/$phase-installed-package.txt"
  code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["variants"][sys.argv[2]]["version_code"])' "$reports/provenance.json" "$phase")
  grep -Eq "versionCode=$code([[:space:]]|$)" "$reports/$phase-installed-package.txt"
  adb shell pm path "$package" | tee "$reports/$phase-installed-path.txt"
  grep -q '^package:' "$reports/$phase-installed-path.txt"
  instrument "$phase" io.github.anastaci1a.localica.ShuffleApkCompatibilityTest 1 -e shuffleCompatibilityPhase "$phase"
  if [[ "$phase" == bridge ]]; then
    instrument bridge-legacy io.github.anastaci1a.localica.LegacyJourneyCompatibilityTest 2
    instrument bridge-journal io.github.anastaci1a.localica.MetadataRefreshJournalTest 6
    for method in failedOverwriteAndRollbackCannotReopenCurrentOrDuplicateSources failedRecoveryRemainsBlockedAndVerifiedLaterRecoveryLoadsPaused explicitPlayDuringVerifiedRecoveryWinsWithoutStaleAutoplay unreadableStartupJournalFailsClosedBeforeAnyNativeSourceOpen missingBackupRemainsSourceScopedAndUnrelatedPlaybackStillWorks mismatchedAndUnfinishedJournalsFailClosedBeforeStartupOpening retainedUnsafeJournalCannotBeReplacedByEditingAnotherSong; do
      instrument "bridge-recovery-$method" "io.github.anastaci1a.localica.QueueRecoveryTest#$method" 1
    done
  fi
done
