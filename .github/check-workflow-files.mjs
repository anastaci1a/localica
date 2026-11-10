#!/usr/bin/env node
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';

const approved = new Set(['android.yml', 'prototype.yml', 'documentation.yml', 'dependency-cache.yml', 'tooling-contracts.yml'].map(name => `.github/workflows/${name}`));
const git = (...args) => execFileSync('git', args, {encoding: 'utf8', maxBuffer: 16 * 1024 * 1024});
const zero = value => !value || /^0+$/.test(value);
function commit(value) {
  if (!/^[0-9a-f]{40}$/.test(value || '')) throw new Error('Expected a full commit SHA');
  try { git('cat-file', '-e', `${value}^{commit}`); }
  catch { git('fetch', '--no-tags', 'origin', value); }
  return value;
}
// Accepted guard history predates this policy and includes the retired identity.yml.
const policyBase = commit('8cfda782947b15d73c601a191fab900f2f5c0a7e');
let base, head;
if (process.argv[2] === '--range') {
  [, , , base, head] = process.argv;
} else {
  const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  if (process.env.GITHUB_EVENT_NAME === 'pull_request') {
    head = commit(event.pull_request.head.sha);
    base = git('merge-base', commit(event.pull_request.base.sha), head).trim();
  } else if (process.env.GITHUB_EVENT_NAME === 'push') {
    head = event.after;
    base = event.before;
    if (zero(head)) {
      console.log('Deleted ref: no workflow files to check.');
      process.exit(0);
    }
  } else {
    head = process.env.GITHUB_SHA;
  }
}
head = commit(head);
if (zero(base)) base = git('merge-base', 'refs/remotes/origin/main', head).trim();
else base = commit(base);
const errors = [];
const files = git('ls-tree', '-r', '-z', head, '--', '.github/workflows/').split('\0').filter(Boolean);
const found = new Set();
for (const entry of files) {
  const [metadata, name] = entry.split('\t');
  found.add(name);
  if (!approved.has(name) || !/^100(?:644|755) blob /.test(metadata)) errors.push(`Unapproved workflow file: ${name}`);
}
for (const name of approved) if (!found.has(name)) errors.push(`Missing approved workflow file: ${name}`);
// Inspect each introduced commit: a transient extra workflow must fail even if removed later.
const commits = git('rev-list', head, `^${base}`, `^${policyBase}`).trim().split('\n').filter(Boolean);
for (const sha of commits) {
  // Disabling rename detection treats every rename/copy destination as an addition.
  const added = git('diff-tree', '--root', '-r', '-m', '--no-commit-id', '--no-renames', '--diff-filter=A', '--name-only', '-z', sha, '--', '.github/workflows/').split('\0').filter(Boolean);
  for (const name of new Set(added)) errors.push(`${sha}: added or renamed workflow file: ${name}`);
}
if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`Workflow file policy passed for ${commits.length} commits; exactly five approved files.`);
