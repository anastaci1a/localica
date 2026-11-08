#!/usr/bin/env bash
# Counts the dependency files Gradle downloaded during a job; the restored cache should already hold them (#681).
#   snapshot  after the cache restore, before the first Gradle invocation
#   report    after the compile step; prints dependency-downloads=N (and the module coordinates), appends it to
#             prototype-runner-environment.txt when that file exists, and annotates the run when N > 0.
# Never fails the job: a branch that changes dependencies must still build.
set -euo pipefail
mode=${1:?usage: dependency-cache-audit.sh snapshot|report}
home=${GRADLE_USER_HOME:-$HOME/.gradle}
state=${RUNNER_TEMP:-${TMPDIR:-/tmp}}/dependency-cache-audit
identity=${DEPENDENCY_CACHE_AUDIT_IDENTITY:-prototype-runner-environment.txt}

modules() {
  # Paths relative to the caches directory: modules-2/files-2.1/<group>/<name>/<version>/<hash>/<file>.
  # Only the module directories are walked; a Gradle JVM may still be removing other cache entries.
  if [[ -d "$home/caches" ]]; then
    (cd "$home/caches" && { find modules-* -path '*/files-*/*' -type f -print 2>/dev/null || true; }) | LC_ALL=C sort
  fi
}

case $mode in
  snapshot)
    mkdir -p "$state"
    modules > "$state/restored.txt"
    printf 'dependency-cache-restored-files=%s\n' "$(wc -l < "$state/restored.txt")"
    ;;
  report)
    if [[ ! -f "$state/restored.txt" ]]; then
      echo 'dependency-cache-audit: no snapshot; run "snapshot" after the cache restore first' >&2
      exit 2
    fi
    modules > "$state/after.txt"
    LC_ALL=C comm -13 "$state/restored.txt" "$state/after.txt" > "$state/downloaded.txt"
    count=$(wc -l < "$state/downloaded.txt")
    {
      printf 'dependency-downloads=%s\n' "$count"
      awk -F/ 'NF >= 7 { print "dependency-downloaded: " $3 ":" $4 ":" $5 }' "$state/downloaded.txt" | LC_ALL=C sort -u
    } > "$state/report.txt"
    cat "$state/report.txt"
    if [[ -f "$identity" ]]; then
      cat "$state/report.txt" >> "$identity"
    fi
    if (( count > 0 )); then
      echo "::notice title=Dependency cache incomplete::$count dependency files were downloaded; the restored Gradle cache did not cover this job (#681)"
    fi
    ;;
  *)
    echo "dependency-cache-audit: unknown mode '$mode'" >&2
    exit 2
    ;;
esac
