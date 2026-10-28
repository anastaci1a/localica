#!/usr/bin/env bash
# Nine TagEditor readiness/theme methods independently on API26/36; not a full suite. Built debug inputs are internal diagnostic artifacts only, never certified/canonical delivery.
set -euo pipefail
# Never consume the user's canonical signing override in this diagnostic lane.
unset LOCALICA_DEBUG_KEYSTORE
[[ $# == 5 ]] || { echo 'usage: run-tageditor-readiness.sh prepare|run CASE SOURCE TOOLING EVIDENCE' >&2; exit 2; }
phase=$1 case_id=$2 source_root=$3 tooling_root=$4 evidence=$5
package=io.github.anastaci1a.localica
tooling_sha=1e31403cb042e9896686200a4800faffce9b2a59
case "$case_id" in
  tag26|tag36)
    source_sha=c693eeaee2e68d3f68e10edb7d4e0b86c63ecfd0 api=${case_id#tag}
    source_parent=408e9fb7726b76e0baa7b0d9aae3b8d2573110d2 source_tree=ce3b90a55eb9b2b89eec1b46aececa58adcb3824
    classes='io.github.anastaci1a.localica.TagEditorThemeInheritanceTest'
    selector='io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens,io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen,io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumArtistInformationAndFooterEditKeepTheirArtworkPolarity,io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumInformationAndFooterEditKeepTheirArtworkPolarity,io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#artistInformationAndFooterEditKeepTheirArtworkPolarity,io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#customLegacyProvidersAndExplicitInformationEditRemainAuthoritative,io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost,io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure,io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching' ;;
  *) echo 'Unknown fixed diagnostic case' >&2; exit 2 ;;
esac
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'Corrected source commit pin is not bound' >&2; exit 2; }
[[ "$phase" == prepare || "$phase" == run ]] || exit 2
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || { echo 'Requires a disposable GitHub-hosted runner' >&2; exit 2; }
[[ ${GITHUB_REPOSITORY:-} == anastaci1a/localica && ${GITHUB_REF:-} == refs/heads/test/hosted-tageditor-canonical-action-20261028 ]] || { echo 'Wrong repository or opt-in branch' >&2; exit 2; }
[[ ${GITHUB_EVENT_NAME:-} == push ]] || { echo 'Only the explicitly approved branch push is supported' >&2; exit 2; }
[[ ${GITHUB_SHA:-} =~ ^[0-9a-f]{40}$ && ${GITHUB_RUN_ID:-} =~ ^[0-9]+$ && ${GITHUB_RUN_ATTEMPT:-} =~ ^[0-9]+$ ]] || exit 2
[[ "$source_root" == "$GITHUB_WORKSPACE/source" && "$tooling_root" == "$GITHUB_WORKSPACE/tooling" && "$evidence" == "$GITHUB_WORKSPACE/tageditor-readiness-evidence-$case_id" ]] || { echo 'Unexpected checkout/evidence paths' >&2; exit 2; }
[[ ! -L "$source_root" && ! -L "$tooling_root" && ! -L "$evidence" ]] || exit 2
[[ $(git -C "$source_root" rev-parse HEAD) == "$source_sha" && $(git -C "$tooling_root" rev-parse HEAD) == "$tooling_sha" && $(git -C "$GITHUB_WORKSPACE/harness" rev-parse HEAD) == "$GITHUB_SHA" ]] || { echo 'Checkout identity mismatch' >&2; exit 2; }
[[ $(git -C "$source_root" show -s --format=%P HEAD) == "$source_parent" && $(git -C "$source_root" rev-parse HEAD^{tree}) == "$source_tree" ]] || { echo 'Corrected source parent/tree mismatch' >&2; exit 2; }
[[ "$source_parent" =~ ^[0-9a-f]{40}$ ]] || { echo 'Readiness parent commit pin is not bound' >&2; exit 2; }
[[ $(git -C "$source_root" show -s --format=%P "$source_parent") == f7220eab208a21158ce4e96c5bdd6738ba23ad0b && $(git -C "$source_root" rev-parse "$source_parent^{tree}") == e3a1af82e054c90d9a79dac9f75f80b99e20f243 ]] || { echo 'Readable-fixture parent ancestry/tree mismatch' >&2; exit 2; }
# Keep the original readiness-to-integrated baseline ancestry gate as well.
[[ $(git -C "$source_root" show -s --format=%P f7220eab208a21158ce4e96c5bdd6738ba23ad0b) == 77314e80885f4649d58482991ac3e2f9898b7d8a && $(git -C "$source_root" rev-parse f7220eab208a21158ce4e96c5bdd6738ba23ad0b^{tree}) == 84f12720cf1c87ccdca94d567efd5298e52cccf1 ]] || { echo 'Readiness baseline ancestry/tree mismatch' >&2; exit 2; }
[[ $(git -C "$source_root" diff-tree --no-commit-id --name-status -r "$source_parent" "$source_sha") == $'M\tapp/src/androidTest/java/io/github/anastaci1a/localica/TagEditorThemeInheritanceTest.kt' ]] || { echo 'Test-only child contains unrelated changes' >&2; exit 2; }
git -C "$source_root" diff --quiet
git -C "$source_root" diff --cached --quiet
git -C "$tooling_root" diff --quiet
git -C "$tooling_root" diff --cached --quiet
git -C "$GITHUB_WORKSPACE/harness" diff --quiet
git -C "$GITHUB_WORKSPACE/harness" diff --cached --quiet
device_output=/sdcard/Android/media/io.github.anastaci1a.localica.debug/additional_test_output
# Keep only these disposable diagnostic installs long enough to pull originals.
# AGP's default uninstall would remove app-scoped files before post-command capture.
command=(./gradlew connectedDebugAndroidTest --no-daemon --max-workers=2 "-Pandroid.testInstrumentationRunnerArguments.class=$selector" "-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=$device_output" -Pandroid.enableAdditionalTestOutput=true -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true)
supervisor=(python3 "$tooling_root/.github/test-evidence/supervise.py" --output "$evidence/live" --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" --sha "$source_sha" --api "$api" --lane "tageditor-readiness-$case_id" -- "${command[@]}")
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
    source_parent='408e9fb7726b76e0baa7b0d9aae3b8d2573110d2', source_tree='ce3b90a55eb9b2b89eec1b46aececa58adcb3824',
    source_parent_parent='f7220eab208a21158ce4e96c5bdd6738ba23ad0b', source_parent_tree='e3a1af82e054c90d9a79dac9f75f80b99e20f243', source_changes=['app/src/androidTest/java/io/github/anastaci1a/localica/TagEditorThemeInheritanceTest.kt'],
    tooling_sha='1e31403cb042e9896686200a4800faffce9b2a59', api=api,
    run_id=os.environ['GITHUB_RUN_ID'], attempt=os.environ['GITHUB_RUN_ATTEMPT'],
    selectors=selector.split(','), argv=invocation[invocation.index('--')+1:], supervisor_argv=invocation,
    helper_sha256={p: hashlib.sha256((pathlib.Path(tooling)/p).read_bytes()).hexdigest() for p in paths},
    harness_sha256={p: hashlib.sha256((pathlib.Path(os.environ['GITHUB_WORKSPACE'])/'harness'/p).read_bytes()).hexdigest() for p in ['.github/workflows/hosted-tageditor-readiness.yml', '.github/run-tageditor-readiness.sh', '.github/reconcile-tageditor-readiness.py', '.github/tageditor_environment.py', '.github/tageditor_java.py']},
    release_certified=False, full_suite_run=False, compatibility_run=False, optical_acceptance=False, causal_claim=False)
inventory = json.loads((pathlib.Path(root)/'expected-methods.json').read_text())
expected_inventory = {'schema_version': 1,
 'selectors': ['io.github.anastaci1a.localica.TagEditorThemeInheritanceTest'],
 'methods': ['io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumArtistInformationAndFooterEditKeepTheirArtworkPolarity',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumInformationAndFooterEditKeepTheirArtworkPolarity',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#artistInformationAndFooterEditKeepTheirArtworkPolarity',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#customLegacyProvidersAndExplicitInformationEditRemainAuthoritative',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure',
             'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching'],
 'runtime_methods': ['io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumArtistInformationAndFooterEditKeepTheirArtworkPolarity',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumInformationAndFooterEditKeepTheirArtworkPolarity',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#artistInformationAndFooterEditKeepTheirArtworkPolarity',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#customLegacyProvidersAndExplicitInformationEditRemainAuthoritative',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure',
                     'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching'],
 'classes': [{'class': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest',
              'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/TagEditorThemeInheritanceTest.kt',
              'sha256': '7a8e8c62b977a61db03f5ba44d10c76c0f3414f27d7c3f6b6e53f7e798d94a0b',
              'methods': ['io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumInformationAndFooterEditKeepTheirArtworkPolarity',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#artistInformationAndFooterEditKeepTheirArtworkPolarity',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumArtistInformationAndFooterEditKeepTheirArtworkPolarity',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#customLegacyProvidersAndExplicitInformationEditRemainAuthoritative',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens',
                          'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure'],
              'declarations': [{'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#actualCollectionMenuAndNestedReviewKeepLaunchThemeAcrossReopen',
                                'line': 122},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumInformationAndFooterEditKeepTheirArtworkPolarity',
                                'line': 164},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#artistInformationAndFooterEditKeepTheirArtworkPolarity',
                                'line': 165},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#albumArtistInformationAndFooterEditKeepTheirArtworkPolarity',
                                'line': 166},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#playerArtworkTapAndMenuCaptureInsideThePlayerInsteadOfItsOuterHost',
                                'line': 213},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#customLegacyProvidersAndExplicitInformationEditRemainAuthoritative',
                                'line': 296},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#unspecifiedAmbientInkFallsBackToTheCurrentPaletteBeforeLaunching',
                                'line': 324},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#acceptedBackgroundSessionKeepsItsPresentationWhenTheJobsWindowReopens',
                                'line': 345},
                               {'id': 'io.github.anastaci1a.localica.TagEditorThemeInheritanceTest#presentationOwnershipChangesOnlyForAnAcceptedSessionNotTracksBusyNoopOrFailure',
                                'line': 370}]}],
 'method_count': 9,
 'runtime_discovery_proven': False,
 'limits': 'Static direct JUnit4 declarations only. Runtime '
           'missing/extra/duplicate IDs must fail closed; '
           'inherited/generated/parameterized/SDK-filter discovery is not '
           'inferred.'}
if inventory != expected_inventory: raise ValueError('Pinned declared source inventory differs')
class_methods = inventory['runtime_methods']
context['expected_methods'] = sorted(selector.split(',') if '#' in selector else class_methods)
with (pathlib.Path(root)/'context.json').open('x') as out: json.dump(context, out, indent=2); out.write('\n')
PY
  python3 "$GITHUB_WORKSPACE/harness/.github/tageditor_java.py" "$evidence" build
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
python3 "$GITHUB_WORKSPACE/harness/.github/tageditor_java.py" "$evidence" native
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
import json, pathlib, re, sys
root, api, serial = sys.argv[1:]
raw=(pathlib.Path(root)/'device.txt').read_text()
def effective(label):
    entries=re.findall(r'^(Physical|Override) '+label+r': (.+)$',raw,re.M)
    if not entries or len({kind for kind,value in entries})!=len(entries): raise ValueError('Missing/ambiguous '+label)
    return dict(entries).get('Override',dict(entries).get('Physical'))
size=[int(x) for x in effective('size').split('x')]
density=int(effective('density'))
scales={}
for name in ('animator_duration_scale','transition_animation_scale','window_animation_scale'):
    values=re.findall(r'^'+name+r'=(.+)$',raw,re.M)
    if len(values)!=1: raise ValueError('Missing/ambiguous '+name)
    scales[name]=float(values[0])
profile={'26':([1080,1920],420,0.0),'36':([720,1280],280,1.0)}
if api not in profile: raise ValueError('Unexpected API')
want_size,want_density,want_animation=profile[api]
if serial!='emulator-5554' or size!=want_size or density!=want_density or any(v!=want_animation for v in scales.values()): raise ValueError('Unexpected actual API profile')
with (pathlib.Path(root)/'device.json').open('x') as out: json.dump(dict(api=api,serial=serial,effective_size=size,effective_density=density,animation_scales=scales),out); out.write('\n')
PY

python3 "$GITHUB_WORKSPACE/harness/.github/tageditor_environment.py" "$evidence" "$api"

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
  local label=$1 code; shift
  timeout 10 "$@" > "$evidence/$label.txt" 2>&1
  code=$?
  printf '%s=%s\n' "$label" "$code" >> "$evidence/capture-status.txt"
  return "$code"
}
finish() {
  local result=$1
  trap '' INT TERM HUP
  trap - EXIT
  set +e
  # Always preserve original diagnostics before emulator teardown or GSA restore.
  capture_state boundary-end adb -s "$ANDROID_SERIAL" shell log -t TagEditorValidation -p i "END run=$GITHUB_RUN_ID attempt=$GITHUB_RUN_ATTEMPT source=$source_sha api=$api"
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
# Delimit this one attempt without deleting pre-existing original logcat.
# Missing/wrapped/duplicate boundaries cannot become passing evidence.
capture_state boundary-start adb -s "$ANDROID_SERIAL" shell log -t TagEditorValidation -p i "START run=$GITHUB_RUN_ID attempt=$GITHUB_RUN_ATTEMPT source=$source_sha api=$api" || exit 3
native_spawn_active=true
"${supervisor[@]}" &
child=$!
wait "$child"
native_exit=$?
native_spawn_active=false
child=''
exit "$native_exit"
