#!/usr/bin/env node
import fs from 'node:fs';
import {spawnSync} from 'node:child_process';

// Keep the original tooling coverage while metadata checks run on every push/body edit.
const paths = [
  '.github/workflows/**', '.github/actions/**', 'app/src/androidTest/java/**',
  '.github/tests/**', '.github/compatibility/**', '.github/test-evidence/**',
  '.github/emulator-sdk.py', '.github/shared-test-apks.py',
  '.github/prebuilt-device-tests.gradle', '.github/release-verification.py',
  '.github/run-device-tests.sh', '.github/prepare-device-tests.sh',
  '.github/run-prototype-tests.sh', '.github/prototype-risk-classes.txt',
  '.github/emulator-readiness.py', '.github/emulator-system-image.py',
  '.github/dependency-cache-audit.sh', '.github/gradle-transient-retry.sh',
  '.github/check-*.mjs', '.github/select-tooling-contracts.mjs', 'gradle.properties',
];
const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
const name = process.env.GITHUB_EVENT_NAME;
const zero = /^0+$/;
let selected = true;
let comparison;
if (name === 'push') {
  if (zero.test(event.after)) selected = false;
  else comparison = [zero.test(event.before) ? 'refs/remotes/origin/main' : event.before, event.after];
} else if (name === 'pull_request') {
  if (event.action === 'edited') selected = false;
  else comparison = [`${event.pull_request.base.sha}...${event.pull_request.head.sha}`];
}
if (comparison) {
  const result = spawnSync('git', ['diff', '--quiet', ...comparison, '--', ...paths], {encoding: 'utf8'});
  if (result.error) throw result.error;
  if (result.status !== 0 && result.status !== 1) throw new Error(result.stderr || 'Cannot determine tooling changes');
  selected = result.status === 1;
}
fs.appendFileSync(process.env.GITHUB_OUTPUT, `contracts=${selected}\n`);
console.log(`Tooling contracts ${selected ? 'selected' : 'not required for these changes'}.`);
