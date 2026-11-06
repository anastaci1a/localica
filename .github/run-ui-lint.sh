#!/usr/bin/env bash
set -euo pipefail
umask 0002
out=${1:?Report directory required}
mkdir -p "$out"
head_sha=$(git rev-parse HEAD)
# Fetch once, then use only the immutable object in the comparison and receipt.
git fetch --no-tags origin main
base_sha=$(git rev-parse FETCH_HEAD)
export UI_LINT_HEAD_SHA="$head_sha" UI_LINT_OUT="$out"
node --input-type=module - <<'JS'
import fs from 'node:fs';
const stage = JSON.parse(fs.readFileSync('tools/ui-consolidation/stage.json','utf8'));
fs.writeFileSync(process.env.UI_LINT_OUT+'/completed-steps.json',JSON.stringify({schemaVersion:1,
    sourceSha:process.env.UI_LINT_HEAD_SHA,steps:stage.completedSteps,release:stage.release}));
JS
stage=$(node -p 'JSON.parse(require("fs").readFileSync("tools/ui-consolidation/stage.json")).stage')
node .github/check-ui-lint.mjs check --base "$base_sha" --head "$head_sha" \
 --baseline app/lint-baseline.xml --escapes app/ui-escapes.yaml --stage "$stage" \
 --completed-steps "$out/completed-steps.json" --out "$out"
