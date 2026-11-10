#!/usr/bin/env node
// Reconcile the independent non-debuggable #803 diagnostic, including every native method.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const text = read('instrumentation.txt');
const expected = [];
for (const name of ['QueueSequenceTest', 'QueueTraversalTest', 'QueueOrderBlobProbeTest']) {
  const source = fs.readFileSync(`app/src/androidTest/java/io/github/anastaci1a/localica/${name}.kt`, 'utf8');
  for (const match of source.matchAll(/@Test\s+fun\s+(\w+)\s*\(/g)) {
    expected.push(`io.github.anastaci1a.localica.${name}#${match[1]}`);
  }
}
assert.equal(expected.length, 16, 'Unexpected source method count');
const statuses = [];
let fields = {};
for (const line of text.split(/\r?\n/)) {
  const field = /^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$/.exec(line);
  const status = /^INSTRUMENTATION_STATUS_CODE: (-?\d+)$/.exec(line);
  if (field) fields[field[1]] = field[2];
  else if (status) {
    const code = Number(status[1]);
    if (code !== 1 && fields.class && fields.test) {
      statuses.push({method: `${fields.class}#${fields.test}`, code});
    }
    fields = {};
  }
}
const observed = statuses.map(item => item.method);
const receipt = {expected_methods: expected.sort(), observed_methods: statuses,
  production_restore_accepted: false, qualified_frame_timing: false};
fs.writeFileSync(path.join(root, 'methods.json'), JSON.stringify(receipt, null, 2) + '\n');
assert.equal(observed.length, new Set(observed).size, 'Repeated terminal method result');
assert.deepEqual(observed.slice().sort(), expected, 'Native method inventory differs from source');
assert(statuses.every(item => item.code === 0), 'A diagnostic method failed, errored or skipped');
assert(/OK \(16 tests\)/.test(text), 'Missing complete JUnit pass');
assert(/^INSTRUMENTATION_CODE: -1\r?$/m.test(text), 'Missing successful instrumentation completion');
const log = read('logcat.txt');
for (const count of [13400, 50000, 1000000]) {
  assert(new RegExp(`QueueOrderBlobProbe.*count=${count} bytes=\\d+ elapsedNanos=\\d+.*nativePeak=\\d+.*managedPeak=\\d+.*gc=`).test(log),
    `Missing scale evidence: ${count}`);
}
for (const [window, chunkBytes] of [16384, 1048576, 262144, 16384].entries()) {
  const lines = log.split(/\r?\n/).filter(line => line.includes('QueueOrderBlobProbe') &&
    line.includes('count=1000000 bytes=') && line.includes('chunkBytes=' + chunkBytes + ' window=' + window + ' '));
  assert(lines.length === 1 && lines[0].includes('gcBefore=') && lines[0].includes(' gc='),
    'Missing or repeated million-ID chunk/baseline receipt: window ' + window + ', chunk ' + chunkBytes);
}
assert(log.includes('bytecode=') && log.includes('sqlite='), 'Missing actual SQLite identity or bytecode');
console.log('All 16 native diagnostic methods passed; production restore acceptance remains open.');
