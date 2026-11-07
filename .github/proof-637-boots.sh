#!/bin/bash
# Proof-branch only (#637): repeated API 36 guest cold boots, each followed by the CI
# display overrides and the exact readiness gate from this tree. Stops at the first failure.
set -u
mkdir -p proof
boots=${PROOF_BOOTS:?}
for i in $(seq 1 "$boots"); do
  if [ "$i" -gt 1 ]; then
    # Fresh launcher on every boot: drop the persisted overrides, then reboot the guest.
    adb shell wm size reset; adb shell wm density reset
    adb reboot
    timeout 120 adb wait-for-device || { echo "boot=$i result=device-lost-after-reboot" | tee -a proof/boots.txt; exit 1; }
    deadline=$((SECONDS + 300))
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
      [ "$SECONDS" -lt "$deadline" ] || { echo "boot=$i result=boot-timeout" | tee -a proof/boots.txt; exit 1; }
      sleep 1
    done
  fi
  start=$SECONDS
  adb shell wm size 720x1280
  adb shell wm density 280
  if python3 .github/emulator-readiness.py --normalize-navigation --output "proof/readiness-$i.json" > /dev/null; then
    echo "boot=$i result=ready readiness_s=$((SECONDS - start)) uptime=$(adb shell cat /proc/uptime | cut -d' ' -f1)" | tee -a proof/boots.txt
  else
    echo "boot=$i result=not-ready adb_state=$(adb get-state 2>&1 | tr -d '\r')" | tee -a proof/boots.txt
    exit 1
  fi
done
echo "all $boots boots ready" | tee -a proof/boots.txt
