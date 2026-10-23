#!/usr/bin/env bash
# Keep unrelated preinstalled apps out of the disposable API 26 test environment.
set -euo pipefail

# Never change a developer's local emulator or a physical device.
if [[ ${GITHUB_ACTIONS:-} != true || ${RUNNER_ENVIRONMENT:-} != github-hosted ]]; then
  exit 0
fi
emulator=$(adb shell getprop ro.kernel.qemu | tr -d '\r')
[[ "$emulator" == 1 ]] || {
  echo 'Hosted native test setup requires an emulator' >&2
  exit 2
}
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$api" =~ ^[0-9]+$ ]] || { echo 'Missing emulator API level' >&2; exit 2; }
[[ "$api" == 26 ]] || exit 0

# The API 26 image's Google Search background jobs can overflow JobScheduler and
# leave an Application Error dialog above the app under test. Stop only that
# unrelated package before instrumentation; never dismiss crashes during tests.
package=com.google.android.googlequicksearchbox
installed=$(adb shell pm list packages "$package" | tr -d '\r')
if grep -Fxq "package:$package" <<< "$installed"; then
  echo "API 26 emulator setup: force-stopping preinstalled $package"
  adb shell am force-stop --user 0 "$package"
fi
