#!/usr/bin/env node
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';

const approvedEmails = new Set([
  'anastaci1a@users.noreply.github.com',
  '48846277+anastaci1a@users.noreply.github.com',
]);
const approvedNames = new Set(['anastaci1a', 'Ana Jahnel']);
const zero = /^0+$/;
const git = (...args) => execFileSync('git', args, {encoding: 'utf8', maxBuffer: 64 * 1024 * 1024});
const errors = [];

function checkTrailers(text, context) {
  for (const line of text.split('\n')) {
    if (/^\s*(claude-session\s*:|🤖\s*Generated with)/i.test(line)) {
      errors.push(`${context}: generated attribution trailer`);
    }
    if (/^\s*co-authored-by\s*:/i.test(line)) {
      const identity = /^\s*co-authored-by\s*:\s*(.*?)\s*<([^<>]+)>\s*$/i.exec(line);
      if (!identity || !approvedNames.has(identity[1]) || !approvedEmails.has(identity[2])) {
        errors.push(`${context}: unapproved coauthor`);
      }
    }
  }
}

function ensureCommit(sha) {
  if (!/^[0-9a-f]{40}$/.test(sha)) throw new Error('Expected a full commit hash');
  try { git('cat-file', '-e', `${sha}^{commit}`); }
  catch { git('fetch', '--no-tags', 'origin', sha); }
  return sha;
}

function range(head, base, remote) {
  ensureCommit(head);
  if (base && !zero.test(base)) return git('rev-list', head, `^${ensureCommit(base)}`).trim().split('\n').filter(Boolean);
  if (remote) return git('rev-list', head, '--not', `--remotes=${remote}`).trim().split('\n').filter(Boolean);
  return git('rev-list', head).trim().split('\n').filter(Boolean);
}

let commits = [];
if (process.argv[2] === '--pre-push') {
  const remote = process.argv[3] || 'origin';
  for (const line of fs.readFileSync(0, 'utf8').trim().split('\n').filter(Boolean)) {
    const [, head, , base] = line.split(/\s+/);
    if (!zero.test(head)) commits.push(...range(head, base, remote));
  }
} else if (process.argv[2] === '--range') {
  commits = range(process.argv[4], process.argv[3]);
} else {
  const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  if (process.env.GITHUB_EVENT_NAME === 'pull_request') {
    checkTrailers(event.pull_request.body || '', 'Pull request body');
    commits = range(event.pull_request.head.sha, event.pull_request.base.sha);
  } else if (process.env.GITHUB_EVENT_NAME === 'push') {
    commits = range(event.after, event.before);
  } else {
    commits = range(process.env.GITHUB_SHA);
  }
}

commits = [...new Set(commits)];
if (commits.length) {
  const result = execFileSync('git', ['cat-file', '--batch'], {
    input: commits.join('\n') + '\n', maxBuffer: 64 * 1024 * 1024,
  });
  let offset = 0;
  for (const sha of commits) {
    const end = result.indexOf(10, offset);
    const [actual, type, size] = result.subarray(offset, end).toString().split(' ');
    if (actual !== sha || type !== 'commit') throw new Error(`Missing commit ${sha}`);
    offset = end + 1;
    const raw = result.subarray(offset, offset + Number(size)).toString();
    offset += Number(size) + 1;
    const separator = raw.indexOf('\n\n');
    const headers = raw.slice(0, separator).split('\n');
    for (const role of ['author', 'committer']) {
      const line = headers.find(value => value.startsWith(`${role} `));
      const identity = /^(?:author|committer) (.*?) <([^<>]+)> /.exec(line || '');
      if (!identity || !approvedNames.has(identity[1]) || !approvedEmails.has(identity[2])) {
        errors.push(`${sha}: unapproved ${role} identity`);
      }
    }
    checkTrailers(raw.slice(separator + 2), sha);
  }
}
if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`Identity and attribution check passed for ${commits.length} commits.`);
