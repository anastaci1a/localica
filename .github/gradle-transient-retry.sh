#!/usr/bin/env bash
# Runs a Gradle build and re-runs it only after a transient dependency download failure (#681).
# Gradle 8.13 does not retry HTTP 5xx or timeouts on dependency downloads (its network back-off serves publishing only).
# Bounded: at most 3 attempts, waiting 30 s then 60 s. A rerun resumes the build; finished tasks stay up to date.
# Failing tests, compilation errors, lint findings and 4xx responses never retry.
set -uo pipefail
attempts=${GRADLE_TRANSIENT_ATTEMPTS:-3}
read -r -a waits <<< "${GRADLE_TRANSIENT_WAITS:-30 60}"
log=${RUNNER_TEMP:-${TMPDIR:-/tmp}}/gradle-transient-retry.log
identity=${DEPENDENCY_CACHE_AUDIT_IDENTITY:-prototype-runner-environment.txt}
transient='Received status code 5[0-9][0-9] from server|Read timed out|Connection reset|Connection timed out|Premature end of Content-Length|Remote host terminated the handshake'
download='Could not (GET|HEAD|download|get resource|resolve all)'
never='There were failing tests|tests completed, [0-9]+ failed|Compilation error|^e: |Lint found|Execution failed for task .*:(test|lint)[A-Za-z]*'

retries=0
for ((attempt = 1; attempt <= attempts; attempt++)); do
  "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if (( status == 0 )); then
    break
  fi
  if (( attempt < attempts )) && grep -Eq "$transient" "$log" && grep -Eq "$download" "$log" && ! grep -Eq "$never" "$log"; then
    wait=${waits[attempt - 1]:-${waits[-1]}}
    echo "::warning title=Transient dependency download failure::attempt $attempt of $attempts failed on a transient repository error; retrying in $wait s (#681)"
    sleep "$wait"
    retries=$((retries + 1))
    continue
  fi
  break
done
if [[ -f "$identity" ]]; then
  printf 'gradle-transient-retries=%s\n' "$retries" >> "$identity"
fi
exit "$status"
