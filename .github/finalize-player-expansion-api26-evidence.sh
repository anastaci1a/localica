#!/usr/bin/env bash
# Failure fallback only: never overwrite the pre-teardown capture.
set -euo pipefail
evidence="$GITHUB_WORKSPACE/player-expansion-api26-evidence-expansion26"
[[ -d "$evidence" ]] || exit 3
if [[ ! -e "$evidence/host-final" ]]; then
  python3 "$GITHUB_WORKSPACE/harness/.github/capture-player-expansion-api26.py" final --evidence "$evidence" || true
fi
if [[ -f "$evidence/monitor.ready" && ! -e "$evidence/monitor.json" ]]; then
  touch "$evidence/monitor.stop"
  for ((n=0;n<45;n++)); do [[ ! -f "$evidence/monitor.json" ]] || break; sleep 1; done
fi
[[ -f "$evidence/monitor.json" ]] || exit 3
