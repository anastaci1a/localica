#!/bin/bash
# Proof-branch only (#637): repeated true cold boots (new emulator process, -no-snapshot),
# each followed by the CI display overrides and the exact readiness gate from this tree.
# Boot 1 is the android-emulator-runner boot; later boots relaunch with identical options.
set -u
mkdir -p proof
options='-no-window -gpu lavapipe -no-snapshot -noaudio -no-boot-anim -memory 3072 -show-kernel'
for i in $(seq 1 "${PROOF_BOOTS:?}"); do
  if [ "$i" -gt 1 ]; then
    adb emu kill >/dev/null 2>&1 || true
    for _ in $(seq 1 60); do pgrep -f 'qemu-system|emulator -avd' >/dev/null || break; sleep 1; done
    adb kill-server >/dev/null 2>&1; adb start-server >/dev/null 2>&1
    # shellcheck disable=SC2086
    "$ANDROID_HOME/emulator/emulator" -avd test $options > "proof/emulator-$i.log" 2>&1 &
    timeout 180 adb wait-for-device || { echo "boot=$i result=no-device" | tee -a proof/boots.txt; exit 1; }
    deadline=$((SECONDS + 300))
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
      [ "$SECONDS" -lt "$deadline" ] || { echo "boot=$i result=boot-timeout" | tee -a proof/boots.txt; exit 1; }
      sleep 1
    done
    adb shell input keyevent 82
  fi
  start=$SECONDS
  adb shell wm size 720x1280
  adb shell wm density 280
  if python3 .github/emulator-readiness.py --normalize-navigation --output "proof/readiness-$i.json" > /dev/null; then
    echo "boot=$i result=ready readiness_s=$((SECONDS - start))" | tee -a proof/boots.txt
  else
    panic=$(grep -c 'Kernel panic' "proof/emulator-$i.log" 2>/dev/null || echo first-boot-see-job-log)
    echo "boot=$i result=not-ready panic_lines=$panic" | tee -a proof/boots.txt
    exit 1
  fi
done
echo "all $PROOF_BOOTS cold boots ready" | tee -a proof/boots.txt
