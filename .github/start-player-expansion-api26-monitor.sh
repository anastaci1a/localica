#!/usr/bin/env bash
# Invoked after the pinned action installs binaries and creates the disposable AVD.
set -euo pipefail
evidence="$GITHUB_WORKSPACE/player-expansion-api26-evidence-expansion26"
python3 "$GITHUB_WORKSPACE/harness/.github/verify-api26-environment.py" --evidence "$evidence"
[[ ! -e "$evidence/monitor.ready" && ! -e "$evidence/monitor.json" && ! -e "$evidence/monitor.stop" ]]
nohup python3 "$GITHUB_WORKSPACE/harness/.github/capture-player-expansion-api26.py" monitor --evidence "$evidence" > "$evidence/monitor-console.log" 2>&1 < /dev/null &
monitor_pid=$!
for ((n=0;n<30;n++)); do
  [[ ! -f "$evidence/monitor.json" ]] || exit 3
  [[ ! -f "$evidence/monitor.ready" ]] || exit 0
  kill -0 "$monitor_pid" 2>/dev/null || exit 3
  sleep 1
done
exit 3
