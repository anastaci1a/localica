#!/usr/bin/env bash
# Six selected regression/diagnostic methods per fixed API; not a full suite. Built debug inputs are internal diagnostic artifacts only, never certified/canonical delivery.
set -euo pipefail
# Never consume the user's canonical signing override in this diagnostic lane.
unset LOCALICA_DEBUG_KEYSTORE
[[ $# == 5 ]] || { echo 'usage: run-popup-menu-clip-diagnostic.sh prepare|run CASE SOURCE TOOLING EVIDENCE' >&2; exit 2; }
phase=$1 case_id=$2 source_root=$3 tooling_root=$4 evidence=$5
package=io.github.anastaci1a.localica
tooling_sha=1e31403cb042e9896686200a4800faffce9b2a59
case "$case_id" in
  clip26|clip36)
    source_sha=96b36571cca90625542f02c08472c18d79be854d api=${case_id#clip}
    source_parent=4e1c89d61efb7e2c3b3ef9040ed9894316361291 source_tree=aaae8ce4b4da48739a164bfddc9c15c1052ff039
    classes='io.github.anastaci1a.localica.ActivationHapticsTest,io.github.anastaci1a.localica.GlassMenuContentTest,io.github.anastaci1a.localica.GlassMenuViewportTouchTest,io.github.anastaci1a.localica.PopupFamilyEdgeComparisonTest'
    selector='io.github.anastaci1a.localica.ActivationHapticsTest#disabledCanceledAndDraggedButtonsNeverEmitActivationFeedback,io.github.anastaci1a.localica.ActivationHapticsTest#sharedButtonOwnersAndDelegatingWrappersEmitExactlyOncePerActivation,io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability,io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts,io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens,io.github.anastaci1a.localica.PopupFamilyEdgeComparisonTest#realLibraryMenuAndDialogEdgesRetainOriginalsAndProvenance' ;;
  *) echo 'Unknown fixed diagnostic case' >&2; exit 2 ;;
esac
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'Corrected source commit pin is not bound' >&2; exit 2; }
[[ "$phase" == prepare || "$phase" == run ]] || exit 2
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || { echo 'Requires a disposable GitHub-hosted runner' >&2; exit 2; }
[[ ${GITHUB_REPOSITORY:-} == anastaci1a/localica && ${GITHUB_REF:-} == refs/heads/test/hosted-popup-menu-clip-diagnostic-20261027 ]] || { echo 'Wrong repository or opt-in branch' >&2; exit 2; }
[[ ${GITHUB_EVENT_NAME:-} == push ]] || { echo 'Only the explicitly approved branch push is supported' >&2; exit 2; }
[[ ${GITHUB_SHA:-} =~ ^[0-9a-f]{40}$ && ${GITHUB_RUN_ID:-} =~ ^[0-9]+$ && ${GITHUB_RUN_ATTEMPT:-} =~ ^[0-9]+$ ]] || exit 2
[[ "$source_root" == "$GITHUB_WORKSPACE/source" && "$tooling_root" == "$GITHUB_WORKSPACE/tooling" && "$evidence" == "$GITHUB_WORKSPACE/popup-menu-clip-evidence-$case_id" ]] || { echo 'Unexpected checkout/evidence paths' >&2; exit 2; }
[[ ! -L "$source_root" && ! -L "$tooling_root" && ! -L "$evidence" ]] || exit 2
[[ $(git -C "$source_root" rev-parse HEAD) == "$source_sha" && $(git -C "$tooling_root" rev-parse HEAD) == "$tooling_sha" && $(git -C "$GITHUB_WORKSPACE/harness" rev-parse HEAD) == "$GITHUB_SHA" ]] || { echo 'Checkout identity mismatch' >&2; exit 2; }
[[ $(git -C "$source_root" show -s --format=%P HEAD) == "$source_parent" && $(git -C "$source_root" rev-parse HEAD^{tree}) == "$source_tree" ]] || { echo 'Corrected source parent/tree mismatch' >&2; exit 2; }
git -C "$source_root" diff --quiet
git -C "$source_root" diff --cached --quiet
git -C "$tooling_root" diff --quiet
git -C "$tooling_root" diff --cached --quiet
device_output=/sdcard/Android/media/io.github.anastaci1a.localica.debug/additional_test_output
# Keep only these disposable diagnostic installs long enough to pull originals.
# AGP's default uninstall would remove app-scoped files before post-command capture.
command=(./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$selector" "-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=$device_output" -Pandroid.enableAdditionalTestOutput=true -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true)
supervisor=(python3 "$tooling_root/.github/test-evidence/supervise.py" --output "$evidence/live" --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" --sha "$source_sha" --api "$api" --lane "popup-menu-clip-$case_id" -- "${command[@]}")
export TEST_API=$api ANDROID_SERIAL=emulator-5554
cd "$source_root"
if [[ "$phase" == prepare ]]; then
  [[ ! -e "$evidence" ]] || { echo 'Refusing existing evidence; no automatic replay' >&2; exit 2; }
  mkdir "$evidence"
  [[ -z $(git status --porcelain --untracked-files=normal) ]] || { echo 'Source checkout is not fresh' >&2; exit 2; }
  python3 "$tooling_root/.github/test-evidence/source_methods.py" --root "$source_root" --classes "$classes" --output "$evidence/expected-methods.json"
  python3 - "$evidence" "$case_id" "$source_sha" "$api" "$selector" "$tooling_root" "${supervisor[@]}" <<'PY'
import hashlib, json, os, pathlib, sys
root, case, source, api, selector, tooling = sys.argv[1:7]
helpers = ['observe.py', 'supervise.py', 'publish.mjs', 'source_methods.py', 'environment.sh', 'package.json', 'package-lock.json']
paths = ['.github/test-evidence/' + name for name in helpers] + ['.github/prepare-device-tests.sh']
invocation = sys.argv[7:]
context = dict(schema_version=1, case=case, source_sha=source, workflow_sha=os.environ['GITHUB_SHA'],
    source_parent='4e1c89d61efb7e2c3b3ef9040ed9894316361291', source_tree='aaae8ce4b4da48739a164bfddc9c15c1052ff039',
    tooling_sha='1e31403cb042e9896686200a4800faffce9b2a59', api=api,
    run_id=os.environ['GITHUB_RUN_ID'], attempt=os.environ['GITHUB_RUN_ATTEMPT'],
    selectors=selector.split(','), argv=invocation[invocation.index('--')+1:], supervisor_argv=invocation,
    helper_sha256={p: hashlib.sha256((pathlib.Path(tooling)/p).read_bytes()).hexdigest() for p in paths},
    release_certified=False, full_suite_run=False, compatibility_run=False, optical_acceptance=False, causal_claim=False)
inventory = json.loads((pathlib.Path(root)/'expected-methods.json').read_text())
expected_inventory = {'schema_version': 1,
 'selectors': ['io.github.anastaci1a.localica.ActivationHapticsTest',
               'io.github.anastaci1a.localica.GlassMenuContentTest',
               'io.github.anastaci1a.localica.GlassMenuViewportTouchTest',
               'io.github.anastaci1a.localica.PopupFamilyEdgeComparisonTest'],
 'methods': ['io.github.anastaci1a.localica.ActivationHapticsTest#disabledCanceledAndDraggedButtonsNeverEmitActivationFeedback',
             'io.github.anastaci1a.localica.ActivationHapticsTest#filterBubblesPulseOncePerActivationAndNotForSelectionMotionOrHolds',
             'io.github.anastaci1a.localica.ActivationHapticsTest#playerActionsIgnoreEmptySlotsAndDoNotAddActivationPulseToLongClicks',
             'io.github.anastaci1a.localica.ActivationHapticsTest#programmaticStateAndDomainCallbacksRemainSilent',
             'io.github.anastaci1a.localica.ActivationHapticsTest#returnToPositionActionEmitsOnceWithoutPulsingForDirectionChanges',
             'io.github.anastaci1a.localica.ActivationHapticsTest#sharedButtonOwnersAndDelegatingWrappersEmitExactlyOncePerActivation',
             'io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability',
             'io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts',
             'io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens',
             'io.github.anastaci1a.localica.PopupFamilyEdgeComparisonTest#realLibraryMenuAndDialogEdgesRetainOriginalsAndProvenance'],
 'runtime_methods': ['io.github.anastaci1a.localica.ActivationHapticsTest#disabledCanceledAndDraggedButtonsNeverEmitActivationFeedback',
                     'io.github.anastaci1a.localica.ActivationHapticsTest#filterBubblesPulseOncePerActivationAndNotForSelectionMotionOrHolds',
                     'io.github.anastaci1a.localica.ActivationHapticsTest#playerActionsIgnoreEmptySlotsAndDoNotAddActivationPulseToLongClicks',
                     'io.github.anastaci1a.localica.ActivationHapticsTest#programmaticStateAndDomainCallbacksRemainSilent',
                     'io.github.anastaci1a.localica.ActivationHapticsTest#returnToPositionActionEmitsOnceWithoutPulsingForDirectionChanges',
                     'io.github.anastaci1a.localica.ActivationHapticsTest#sharedButtonOwnersAndDelegatingWrappersEmitExactlyOncePerActivation',
                     'io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability',
                     'io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts',
                     'io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens',
                     'io.github.anastaci1a.localica.PopupFamilyEdgeComparisonTest#realLibraryMenuAndDialogEdgesRetainOriginalsAndProvenance'],
 'classes': [{'class': 'io.github.anastaci1a.localica.ActivationHapticsTest',
              'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/ActivationHapticsTest.kt',
              'sha256': '4b58e9e96e4f3c4808162a3117526b767fc35b2b86d989137007beeac0f15295',
              'methods': ['io.github.anastaci1a.localica.ActivationHapticsTest#sharedButtonOwnersAndDelegatingWrappersEmitExactlyOncePerActivation',
                          'io.github.anastaci1a.localica.ActivationHapticsTest#disabledCanceledAndDraggedButtonsNeverEmitActivationFeedback',
                          'io.github.anastaci1a.localica.ActivationHapticsTest#programmaticStateAndDomainCallbacksRemainSilent',
                          'io.github.anastaci1a.localica.ActivationHapticsTest#returnToPositionActionEmitsOnceWithoutPulsingForDirectionChanges',
                          'io.github.anastaci1a.localica.ActivationHapticsTest#playerActionsIgnoreEmptySlotsAndDoNotAddActivationPulseToLongClicks',
                          'io.github.anastaci1a.localica.ActivationHapticsTest#filterBubblesPulseOncePerActivationAndNotForSelectionMotionOrHolds'],
              'declarations': [{'id': 'io.github.anastaci1a.localica.ActivationHapticsTest#sharedButtonOwnersAndDelegatingWrappersEmitExactlyOncePerActivation',
                                'line': 47},
                               {'id': 'io.github.anastaci1a.localica.ActivationHapticsTest#disabledCanceledAndDraggedButtonsNeverEmitActivationFeedback',
                                'line': 64},
                               {'id': 'io.github.anastaci1a.localica.ActivationHapticsTest#programmaticStateAndDomainCallbacksRemainSilent',
                                'line': 81},
                               {'id': 'io.github.anastaci1a.localica.ActivationHapticsTest#returnToPositionActionEmitsOnceWithoutPulsingForDirectionChanges',
                                'line': 94},
                               {'id': 'io.github.anastaci1a.localica.ActivationHapticsTest#playerActionsIgnoreEmptySlotsAndDoNotAddActivationPulseToLongClicks',
                                'line': 103},
                               {'id': 'io.github.anastaci1a.localica.ActivationHapticsTest#filterBubblesPulseOncePerActivationAndNotForSelectionMotionOrHolds',
                                'line': 124}]},
             {'class': 'io.github.anastaci1a.localica.GlassMenuContentTest',
              'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/GlassMenuContentTest.kt',
              'sha256': 'a49b00da270da2e47b3495a7b8c9545d5c767f061a2b7620a59cbbe6e482f553',
              'methods': ['io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts',
                          'io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability'],
              'declarations': [{'id': 'io.github.anastaci1a.localica.GlassMenuContentTest#fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts',
                                'line': 37},
                               {'id': 'io.github.anastaci1a.localica.GlassMenuContentTest#constrainedMenuRetainsScrollRangeAndLastActionReachability',
                                'line': 86}]},
             {'class': 'io.github.anastaci1a.localica.GlassMenuViewportTouchTest',
              'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/GlassMenuViewportTouchTest.kt',
              'sha256': '0f6d9431eadef219d367283d84a4f50522be59a447116cc45fd3dc87cf086375',
              'methods': ['io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens'],
              'declarations': [{'id': 'io.github.anastaci1a.localica.GlassMenuViewportTouchTest#longMenuTouchScrollsToActualBoundaryThenTapsDismissesAndReopens',
                                'line': 60}]},
             {'class': 'io.github.anastaci1a.localica.PopupFamilyEdgeComparisonTest',
              'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/PopupFamilyEdgeComparisonTest.kt',
              'sha256': '19055ac4a907c250d4de6fd2bf1ff59a8762b71ae541f69c55459594e50dfeac',
              'methods': ['io.github.anastaci1a.localica.PopupFamilyEdgeComparisonTest#realLibraryMenuAndDialogEdgesRetainOriginalsAndProvenance'],
              'declarations': [{'id': 'io.github.anastaci1a.localica.PopupFamilyEdgeComparisonTest#realLibraryMenuAndDialogEdgesRetainOriginalsAndProvenance',
                                'line': 76}]}],
 'method_count': 10,
 'runtime_discovery_proven': False,
 'limits': 'Static direct JUnit4 declarations only. Runtime missing/extra/duplicate IDs must fail closed; '
           'inherited/generated/parameterized/SDK-filter discovery is not inferred.'}
if inventory != expected_inventory: raise ValueError('Pinned declared source inventory differs')
class_methods = inventory['runtime_methods']
context['expected_methods'] = sorted(selector.split(',') if '#' in selector else class_methods)
with (pathlib.Path(root)/'context.json').open('x') as out: json.dump(context, out, indent=2); out.write('\n')
PY
  bash "$tooling_root/.github/test-evidence/environment.sh" > "$evidence/build-environment.txt" 2>&1
  exit 0
fi

# Preparation/build failure cannot enter native execution, and no old XML is reused.
[[ -f "$evidence/context.json" && ! -e "$evidence/live" && ! -e "$evidence/runner-exit.txt" ]] || exit 2
[[ -z $(find app/build/outputs/androidTest-results -name '*.xml' -print 2>/dev/null) ]] || { echo 'Refusing stale final XML' >&2; exit 2; }
[[ $(adb devices | awk 'NR>1 && NF {print $1 " " $2}') == "$ANDROID_SERIAL device" ]] || { echo 'Require exactly the selected hosted emulator' >&2; exit 2; }
[[ $(adb -s "$ANDROID_SERIAL" get-serialno | tr -d '\r') == "$ANDROID_SERIAL" ]] || exit 2
[[ $(adb -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || exit 2
[[ $(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r') == "$api" ]] || { echo 'Actual API differs from fixed diagnostic' >&2; exit 2; }
adb -s "$ANDROID_SERIAL" shell "[ ! -e '$device_output' ]" || { echo 'Refusing stale on-device diagnostic output' >&2; exit 2; }
sha256sum app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > "$evidence/test-input-apks.sha256"
for variant in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
  "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$variant"
done > "$evidence/test-input-certificates.txt"
bash "$tooling_root/.github/test-evidence/environment.sh" > "$evidence/native-environment.txt" 2>&1
{
  printf 'serial=%s\nrequested_api=%s\n' "$ANDROID_SERIAL" "$api"
  adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint
  adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
  adb -s "$ANDROID_SERIAL" shell cat /proc/sys/kernel/random/boot_id
  adb -s "$ANDROID_SERIAL" emu avd name
  adb -s "$ANDROID_SERIAL" shell wm size
  adb -s "$ANDROID_SERIAL" shell wm density
  for key in animator_duration_scale transition_animation_scale window_animation_scale; do
    printf '%s=' "$key"; adb -s "$ANDROID_SERIAL" shell settings get global "$key"
  done
} > "$evidence/device.txt"
python3 - "$evidence" "$api" "$ANDROID_SERIAL" <<'PY'
import json, pathlib, sys
root, api, serial = sys.argv[1:]
with (pathlib.Path(root)/'device.json').open('x') as out: json.dump(dict(api=api, serial=serial), out); out.write('\n')
PY

# The unchanged API26 helper installs its lease-checked restoration trap. Install
# our finalizer only afterwards, retaining that exact owned restoration operation.
source "$tooling_root/.github/prepare-device-tests.sh" > "$evidence/package-prepare.txt" 2>&1
guard_active=false
if [[ -n $(trap -p EXIT) ]]; then
  [[ $(trap -p EXIT) == *localica_guard_exit* ]] || { echo 'Unknown existing cleanup trap' >&2; exit 2; }
  guard_active=true
fi
child='' native_exit='' cancel_signal='' cleanup_exit=0 native_spawn_active=false
capture_state() {
  local label=$1; shift
  timeout 10 "$@" > "$evidence/$label.txt" 2>&1
  printf '%s=%s\n' "$label" "$?" >> "$evidence/capture-status.txt"
}
finish() {
  local result=$1
  trap '' INT TERM HUP
  trap - EXIT
  set +e
  # Always preserve original diagnostics before emulator teardown or GSA restore.
  capture_state logcat adb -s "$ANDROID_SERIAL" logcat -d -v threadtime
  capture_state crash-buffer adb -s "$ANDROID_SERIAL" logcat -b crash -d -v threadtime
  capture_state windows adb -s "$ANDROID_SERIAL" shell dumpsys -t 5 window windows
  capture_state activities adb -s "$ANDROID_SERIAL" shell dumpsys -t 5 activity activities
  # Preserve unmodified test-written files even if AGP did not export the explicit
  # output folder. These are original copies, not reruns/replacements of failures.
  timeout 15 adb -s "$ANDROID_SERIAL" pull "$device_output" "$evidence/device-output" > "$evidence/device-output-pull.txt" 2>&1
  printf 'device-output-pull=%s\n' "$?" >> "$evidence/capture-status.txt"
  if [[ "$guard_active" == true ]]; then localica_guard_restore > "$evidence/package-restore.txt" 2>&1; cleanup_exit=$?; fi
  if [[ "$result" == 0 && "$cleanup_exit" != 0 ]]; then result=3; fi
  printf '%s\n' "$result" > "$evidence/runner-exit.txt"
  python3 - "$evidence" "$result" "$native_exit" "$cancel_signal" "$cleanup_exit" "$guard_active" <<'PY'
import json, pathlib, sys
root, result, native, signal, cleanup, guarded = sys.argv[1:]
data = dict(phase='native-terminal', runner_exit=int(result), native_exit=int(native) if native else None,
    cancel_signal=signal or None, cleanup_exit=int(cleanup), package_guard_active=guarded == 'true')
with (pathlib.Path(root)/'status.json').open('x') as out: json.dump(data, out, indent=2); out.write('\n')
PY
  status_write=$?
  [[ "$result" != 0 || "$status_write" == 0 ]] || result=3
  exit "$result"
}
stop_child() {
  local sig=$1 code=$2 forwarded=$1
  # Preserve HUP identity but ask the pinned supervisor to drain via its TERM handler.
  [[ "$sig" != HUP ]] || forwarded=TERM
  cancel_signal=$sig
  trap '' INT TERM HUP
  # Bash can deliver a pending signal between the async launch and child=$!.
  # Recover only this active launch, never a completed/stale background PID.
  if [[ "$native_spawn_active" == true && -z "$child" ]]; then child=${!:-}; fi
  if [[ -n "$child" ]]; then
    kill -s "$forwarded" "$child" 2>/dev/null || true
    set +e; wait "$child"; native_exit=$?; child=''
  fi
  exit "$code"
}
trap 'finish $?' EXIT
trap 'stop_child TERM 143' TERM
trap 'stop_child INT 130' INT
trap 'stop_child HUP 129' HUP
set +e
native_spawn_active=true
"${supervisor[@]}" &
child=$!
wait "$child"
native_exit=$?
native_spawn_active=false
child=''
exit "$native_exit"
