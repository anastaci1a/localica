import assert from 'node:assert/strict';
import {execFileSync, spawnSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const script = fileURLToPath(new URL('../check-workflow-files.mjs', import.meta.url));
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'localica-workflow-policy-'));
const env = {...process.env, GIT_AUTHOR_NAME: 'anastaci1a', GIT_COMMITTER_NAME: 'anastaci1a', GIT_AUTHOR_EMAIL: '48846277+anastaci1a@users.noreply.github.com', GIT_COMMITTER_EMAIL: '48846277+anastaci1a@users.noreply.github.com'};
const git = (...args) => execFileSync('git', args, {cwd: directory, env, encoding: 'utf8'}).trim();
const write = (name, body = 'name: fixture\n') => fs.writeFileSync(path.join(directory, '.github/workflows', name), body);
const save = message => { git('add', '.'); git('commit', '-qm', message); return git('rev-parse', 'HEAD'); };
const check = (base, head, ok) => {
  const result = spawnSync(process.execPath, [script, '--range', base, head], {cwd: directory, env, encoding: 'utf8'});
  assert.equal(result.status, ok ? 0 : 1, result.stdout + result.stderr);
};
try {
  git('init', '-q');
  fs.mkdirSync(path.join(directory, '.github/workflows'), {recursive: true});
  for (const name of ['android.yml', 'prototype.yml', 'documentation.yml', 'dependency-cache.yml', 'tooling-contracts.yml']) write(name);
  const base = save('Base fixtures');
  git('update-ref', 'refs/remotes/origin/main', base);
  write('prototype.yml', 'name: edited fixture\n');
  const edit = save('Edit an approved workflow');
  check(base, edit, true);
  check('0'.repeat(40), edit, true);
  write('one-off.yml');
  const addition = save('Add forbidden diagnostic');
  check(base, addition, false);
  fs.unlinkSync(path.join(directory, '.github/workflows/one-off.yml'));
  check(base, save('Remove transient diagnostic'), false);
  git('reset', '--hard', base);
  git('mv', '.github/workflows/android.yml', '.github/workflows/renamed.yml');
  check(base, save('Rename workflow'), false);
  git('reset', '--hard', base);
  fs.unlinkSync(path.join(directory, '.github/workflows/android.yml'));
  check(base, save('Delete approved workflow'), false);
  git('reset', '--hard', base);
  fs.mkdirSync(path.join(directory, '.github/workflows/nested'));
  write('nested/probe.yml');
  check(base, save('Add nested workflow'), false);
  git('reset', '--hard', base);
  fs.unlinkSync(path.join(directory, '.github/workflows/android.yml'));
  fs.symlinkSync('prototype.yml', path.join(directory, '.github/workflows/android.yml'));
  check(base, save('Replace approved file with symlink'), false);
  git('reset', '--hard', base);
  const event = path.join(directory, 'event.json');
  fs.writeFileSync(event, JSON.stringify({pull_request: {base: {sha: base}, head: {sha: edit}}}));
  assert.equal(spawnSync(process.execPath, [script], {cwd: directory, env: {...env, GITHUB_EVENT_PATH: event, GITHUB_EVENT_NAME: 'pull_request'}}).status, 0);
  fs.writeFileSync(event, JSON.stringify({before: base, after: edit}));
  assert.equal(spawnSync(process.execPath, [script], {cwd: directory, env: {...env, GITHUB_EVENT_PATH: event, GITHUB_EVENT_NAME: 'push'}}).status, 0);
  assert.equal(spawnSync(process.execPath, [script], {cwd: directory, env: {...env, GITHUB_EVENT_PATH: event, GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_SHA: edit}}).status, 0);
  fs.writeFileSync(event, JSON.stringify({before: base, after: '0'.repeat(40)}));
  assert.equal(spawnSync(process.execPath, [script], {cwd: directory, env: {...env, GITHUB_EVENT_PATH: event, GITHUB_EVENT_NAME: 'push'}}).status, 0);
  console.log('Workflow file policy: edits, new refs, additions, transient additions, renames, deletions, nested files, symlinks, push, PR, manual and deleted-ref events passed.');
} finally {
  fs.rmSync(directory, {recursive: true, force: true});
}
