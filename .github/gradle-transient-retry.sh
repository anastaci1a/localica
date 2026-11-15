#!/usr/bin/env bash
# Runs a Gradle build and re-runs it only after a transient dependency download failure (#681).
# Bounded: at most 3 attempts, waiting 30 s then 60 s, extended by Retry-After up to 120 s (#1018).
# A rerun resumes the build; finished tasks stay up to date. Tests, compilation, lint and other 4xx never retry.
set -uo pipefail
attempts=${GRADLE_TRANSIENT_ATTEMPTS:-3}
read -r -a waits <<< "${GRADLE_TRANSIENT_WAITS:-30 60}"
log=${RUNNER_TEMP:-${TMPDIR:-/tmp}}/gradle-transient-retry.log
identity=${DEPENDENCY_CACHE_AUDIT_IDENTITY:-prototype-runner-environment.txt}
transient='Received status code (429|5[0-9][0-9]) from server|Read timed out|Connection reset|Connection timed out|Premature end of Content-Length|Remote host terminated the handshake|Network is unreachable|No route to host|Temporary failure in name resolution'
download='Could not (GET|HEAD|download|get resource|resolve all)'
never='There were failing tests|tests completed, [0-9]+ failed|Compilation error|^e: |Lint found|Execution failed for task .*:(test|lint)[A-Za-z]*'

retry_after() {
  awk 'tolower($0) ~ /^[<>[:space:]]*retry-after:/ { sub(/^[^:]*:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit }'
}

mkdir -p ci-infrastructure/dependency-attempts
retries=0
for ((attempt = 1; attempt <= attempts; attempt++)); do
  "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  cp "$log" "ci-infrastructure/dependency-attempts/attempt-$attempt.log"
  if (( status == 0 )); then
    break
  fi
  if (( attempt < attempts )) && grep -Eq "$transient" "$log" && grep -Eq "$download" "$log" && ! grep -Eq "$never" "$log"; then
    wait=${waits[attempt - 1]:-${waits[-1]}}
    if grep -q 'Received status code 429 from server' "$log"; then
      after=$(retry_after < "$log")
      # Gradle omits response headers; one bounded HEAD probe can recover the server's requested delay.
      if [[ -z "$after" ]]; then
        url=$(sed -nE "/Received status code 429/s/.*Could not (GET|HEAD) '(https?:\/\/[^']+)'.*/\2/p" "$log" | head -n 1)
        if [[ -n "$url" ]]; then
          after=$(curl --silent --head --connect-timeout 5 --max-time 10 "$url" | retry_after)
        fi
      fi
      delay=0
      if [[ "$after" =~ ^[0-9]+$ ]]; then
        delay=121
        (( ${#after} > 9 )) || delay=$((10#$after))
      elif [[ -n "$after" ]] && epoch=$(LC_ALL=C date -d "$after" +%s 2>/dev/null); then
        delay=$((epoch - $(date +%s)))
      fi
      (( delay <= wait )) || wait=$delay
      if (( wait > 120 )); then
        echo "::warning title=Dependency retry budget exceeded::Retry-After requires $wait s; stopping without an early retry (#1018)"
        break
      fi
    fi
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
if (( status != 0 )) && grep -Eq "$transient" "$log" && grep -Eq "$download" "$log" && ! grep -Eq "$never" "$log"; then
  mkdir -p ci-infrastructure
  printf '{"schema_version":1,"classification":"infrastructure","reason":"dependency-network","issue":752,"attempts":%s,"exit_code":%s,"sha":"%s","run_id":"%s","run_attempt":"%s","job":"%s"}\n' "$attempt" "$status" "${GITHUB_SHA:-}" "${GITHUB_RUN_ID:-}" "${GITHUB_RUN_ATTEMPT:-}" "${GITHUB_JOB:-}" > ci-infrastructure/dependencies.json
  echo '::error title=Infrastructure failure (#752)::Dependency downloads exceeded the bounded retry budget; no product verdict.'
fi
exit "$status"
