#!/bin/bash
# Proof-branch only (#637): after the CI cold boot, repeat "density change -> Launcher3
# force-stop -> HOME relaunch". MODE=immediate kills while the launcher still renders
# (the pre-fix gate timing); MODE=idle waits for three identical frames first (the fix).
set -u
mkdir -p proof
idle() {
  local prev='' same=0 h
  for _ in $(seq 1 60); do
    h=$(adb exec-out screencap -p | sha256sum | cut -c1-16)
    if [ "$h" = "$prev" ]; then same=$((same + 1)); else same=1; fi
    prev=$h
    [ "$same" -ge 3 ] && return 0
    sleep 0.5
  done
  return 1
}
adb shell wm size 720x1280
for i in $(seq 1 "${PROOF_CYCLES:?}"); do
  adb shell wm density 420 || { echo "cycle=$i result=device-lost" | tee -a proof/cycles.txt; exit 1; }
  adb shell wm density 280
  adb shell input keyevent KEYCODE_HOME
  if [ "$PROOF_MODE" = idle ]; then idle || echo "cycle=$i idle-timeout" | tee -a proof/cycles.txt; else sleep 0.3; fi
  adb shell am force-stop com.android.launcher3 || { echo "cycle=$i result=device-lost-at-stop" | tee -a proof/cycles.txt; exit 1; }
  adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null || { echo "cycle=$i result=device-lost-at-home" | tee -a proof/cycles.txt; exit 1; }
  echo "cycle=$i result=ok mode=$PROOF_MODE" | tee -a proof/cycles.txt
done
echo "all $PROOF_CYCLES cycles ok mode=$PROOF_MODE" | tee -a proof/cycles.txt
