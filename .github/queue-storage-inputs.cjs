#!/usr/bin/env node
// Proof-only APK linkage and API-specific compiler-filter checks. Raw inputs are retained.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const [mode, root] = process.argv.slice(2);
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const write = (name, value) => fs.writeFileSync(path.join(root, name), value);
if (mode === 'identity') {
  const packageName = file => {
    const match = /^package: name='([^']+)'/m.exec(read(file));
    assert(match, `No APK package in ${file}`);
    return match[1];
  };
  const target = packageName('badging.txt');
  const tests = packageName('test-badging.txt');
  assert.equal(target, 'io.github.anastaci1a.localica.debug', 'Require the disposable development target');
  assert.equal(tests, `${target}.test`, 'Unexpected test APK package');
  const blocks = read('test-manifest.txt').split(/\n(?=\s*E: )/);
  const runners = blocks.filter(block => /^\s*E: instrumentation\b/.test(block)).map(block => ({
    name: /:name\([^)]*\)="([^"]+)"/.exec(block)?.[1],
    target: /:targetPackage\([^)]*\)="([^"]+)"/.exec(block)?.[1],
  })).filter(item => item.target === target);
  assert.equal(runners.length, 1, 'Require exactly one runner targeting the distribution application');
  assert(/^[A-Za-z0-9_.]+$/.test(runners[0].name), 'Invalid runner name');
  write('identity.txt', `${target}\n${tests}\n${runners[0].name}\n`);
  write('identity.json', JSON.stringify({target, tests, runner: runners[0].name, verified: true}, null, 2) + '\n');
} else if (mode === 'dexopt') {
  const api = Number(read('api.txt').trim());
  assert(api === 26 || api === 36, 'Only reviewed API 26/36 receipt formats are supported');
  const target = read('identity.txt').split('\n')[0];
  const dump = read('target-dexopt.txt');
  // dumpsys package <target> is scoped; require its exact package header as well.
  assert(dump.includes(`[${target}]`), 'Target package is absent from its scoped package dump');
  // API 26 ART GetStatusDump emits compilation_filter=. API 36 ART Service emits [status=...].
  const pattern = api === 26 ? /compilation_filter=([^,\]\s]+)/g : /\[status=([^\]\s]+)\]/g;
  const matches = [...dump.matchAll(pattern)].map(match => ({filter: match[1], line: dump.slice(0, match.index).split('\n').length}));
  write('compiler-filter.json', JSON.stringify({api, target, matches, qualified_frame_timing: false}, null, 2) + '\n');
  assert(matches.length > 0 && matches.every(item => item.filter === 'speed-profile'), 'Target compiler filter is not exclusively speed-profile');
} else throw new Error(`Unknown input verification mode: ${mode}`);
