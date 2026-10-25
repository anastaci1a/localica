#!/usr/bin/env bash
# Public toolchain/runner facts only; never dump the environment or credentials.
set -euo pipefail
date -u +%FT%TZ
git rev-parse HEAD
uname -a
nproc
free -m
df -h . "${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
printf 'runner=%s/%s image=%s/%s\n' "${RUNNER_OS:-unknown}" "${RUNNER_ARCH:-unknown}" "${ImageOS:-unknown}" "${ImageVersion:-unknown}"
java -version 2>&1
python3 --version
if command -v node >/dev/null; then node --version; fi
sha256sum gradle/wrapper/gradle-wrapper.properties gradle/libs.versions.toml
if [[ -n ${ANDROID_HOME:-} ]]; then
  for file in "$ANDROID_HOME"/build-tools/35.0.0/source.properties "$ANDROID_HOME"/emulator/source.properties "$ANDROID_HOME"/system-images/android-{26,36}/*/*/source.properties; do
    if [[ -f "$file" ]]; then printf '\n%s\n' "$file"; cat "$file"; fi
  done
fi
