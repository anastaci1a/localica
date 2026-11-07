#!/usr/bin/env bash
# Runs an emulator script the way reactivecircus/android-emulator-runner does (#592): every non-empty,
# non-comment line through `sh -c`, in order, stopping at the first failing line, whose status it returns.
# The lines come from fd 3 and each command gets no stdin, so a line that reads stdin (`adb shell` does)
# cannot swallow the lines after it and end the script early with status 0.
set -euo pipefail
script=${1?usage: run-script-lines.sh SCRIPT}
rc=0
while IFS= read -r line <&3 || [[ -n $line ]]; do
  line=${line#"${line%%[![:space:]]*}"}
  [[ -n $line && $line != \#* ]] || continue
  sh -c "$line" </dev/null || { rc=$?; echo "::error::script line failed ($rc): $line"; break; }
done 3<<< "$script"
exit "$rc"
