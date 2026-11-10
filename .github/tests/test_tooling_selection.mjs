import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync, spawnSync} from 'node:child_process';

const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'tooling-selection-'));
const helper = fileURLToPath(new URL('../select-tooling-contracts.mjs', import.meta.url));
const env = {...process.env, GIT_AUTHOR_NAME: 'anastaci1a', GIT_COMMITTER_NAME: 'anastaci1a',
  GIT_AUTHOR_EMAIL: '48846277+anastaci1a@users.noreply.github.com',
  GIT_COMMITTER_EMAIL: '48846277+anastaci1a@users.noreply.github.com'};
const git = (args, input) => execFileSync('git', ['-C', fixture, ...args], {encoding: 'utf8', env, input}).trim();
let tests = 0;
try {
  git(['init', '-q']);
  const tree = git(['mktree'], '');
  const base = git(['commit-tree', tree], 'Base\n');
  git(['update-ref', 'refs/remotes/origin/main', base]);
  function change(file) {
    git(['read-tree', tree]);
    const blob = git(['hash-object', '-w', '--stdin'], 'Changed fixture\n');
    git(['update-index', '--add', '--cacheinfo', '100644', blob, file]);
    return git(['commit-tree', git(['write-tree']), '-p', base], 'Change\n');
  }
  function check(name, event, expected) {
    const eventPath = path.join(fixture, 'event.json');
    const output = path.join(fixture, 'output');
    fs.writeFileSync(eventPath, JSON.stringify(event));
    fs.writeFileSync(output, '');
    const result = spawnSync('node', [helper], {cwd: fixture, encoding: 'utf8', env: {
      ...env, GITHUB_EVENT_NAME: name, GITHUB_EVENT_PATH: eventPath, GITHUB_OUTPUT: output,
    }});
    if (expected === 'error') assert.notEqual(result.status, 0, result.stdout);
    else {
      assert.equal(result.status, 0, result.stderr);
      assert.equal(fs.readFileSync(output, 'utf8'), `contracts=${expected}\n`);
    }
    tests++;
  }
  const relevant = [
    '.github/workflows/android.yml', '.github/actions/example/action.yml',
    'app/src/main/java/Player.kt', 'app/src/sharedTest/java/Fixture.kt', 'app/src/test/java/OwnerTest.kt',
    'lint/src/main/Detector.kt', 'tools/ui-consolidation/stage.json', 'tools/performance/scenarios.json',
    'app/lint-baseline.xml', 'app/ui-escapes.yaml', 'app/build.gradle.kts', 'settings.gradle.kts',
    'build.gradle.kts', 'gradle/libs.versions.toml', '.github/run-ui-lint.sh',
    'app/src/androidTest/java/example/ExampleTest.kt', '.github/tests/example.mjs',
    '.github/compatibility/run.sh', '.github/test-evidence/source_methods.py',
    '.github/emulator-sdk.py', '.github/shared-test-apks.py',
    '.github/prebuilt-device-tests.gradle', '.github/release-verification.py',
    '.github/run-device-tests.sh', '.github/prepare-device-tests.sh',
    '.github/run-prototype-tests.sh', '.github/prototype-risk-classes.txt',
    '.github/emulator-readiness.py', '.github/emulator-system-image.py',
    '.github/dependency-cache-audit.sh', '.github/gradle-transient-retry.sh',
    '.github/check-commit-identity.mjs', '.github/check-workflow-files.mjs',
    '.github/select-tooling-contracts.mjs', 'gradle.properties',
  ];
  for (const file of relevant) check('push', {before: base, after: change(file)}, true);
  for (const file of ['README.md', 'docs/build.md', 'agents/README.md']) {
    check('push', {before: base, after: change(file)}, false);
  }
  const tooling = change('.github/tests/example.mjs');
  const docs = change('docs/build.md');
  check('push', {before: '0'.repeat(40), after: tooling}, true);
  check('push', {before: '0'.repeat(40), after: docs}, false);
  check('push', {before: tooling, after: '0'.repeat(40)}, false);
  check('pull_request', {action: 'synchronize', pull_request: {base: {sha: base}, head: {sha: tooling}}}, true);
  check('pull_request', {action: 'synchronize', pull_request: {base: {sha: base}, head: {sha: docs}}}, false);
  check('pull_request', {action: 'edited', pull_request: {base: {sha: base}, head: {sha: tooling}}}, false);
  check('workflow_dispatch', {}, true);
  check('workflow_call', {}, true);
  check('push', {before: '1'.repeat(40), after: tooling}, 'error');
  console.log(`${tests}/${tests} tooling selection fixtures passed.`);
} finally {
  fs.rmSync(fixture, {recursive: true});
}
