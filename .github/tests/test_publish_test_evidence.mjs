import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, writeFile, appendFile, readFile, readdir, rm} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {publish, validateEvent} from '../test-evidence/publish.mjs';

const ids = {command_id: '12345678-1234-1234-1234-123456789012', sha: 'a'.repeat(40), api: '26', lane: 'prototype'};
const running = {...ids, schema_version: 1, started_utc: '2026-10-25T18:00:00.000Z', ended_utc: null, status: 'running', observed_failure_lines: 0, argv: ['SECRET_ARG'], observer_error: 'SECRET_ERROR'};
const event = (n = 1) => ({...ids, event: 'test_failure_observed', provisional: true, utc: '2026-10-25T18:00:01.000Z', observation_index: n, stream: 'stdout', stream_line: n, stream_byte_offset: n * 100, class: 'example.SomeTest', method: 'fails', display_name: 'fails[emulator-5554]', parsed_line: 'SECRET_RAW', environment: 'SECRET_ENV'});
async function fixture(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'publisher-test-'));
  t.after(() => rm(root, {recursive: true, force: true}));
  const evidenceDir = path.join(root, 'observed'), stateDir = path.join(root, 'publishing');
  await mkdir(evidenceDir);
  const manifest = value => writeFile(path.join(evidenceDir, 'manifest.json'), JSON.stringify(value));
  let count = 0;
  const append = value => { if (typeof value !== 'string') count++; return appendFile(path.join(evidenceDir, 'events.jsonl'), typeof value === 'string' ? value : JSON.stringify(value) + '\n'); };
  const done = () => manifest({...running, status: 'failed', observed_failure_lines: count, ended_utc: '2026-10-25T18:00:05.000Z'});
  await manifest(running); await append('');
  const uploads = [];
  const options = {evidenceDir, stateDir, runId: '123', attempt: '2',
    client: {async uploadArtifact(name, files, root, opts) {
      const packet = JSON.parse(await readFile(files[0], 'utf8'));
      uploads.push({name, packet, root, opts}); return {id: uploads.length};
    }}, wait: async () => { throw new Error('unexpected_wait'); }};
  return {options, uploads, append, done, manifest, stateDir};
}

test('first failure is uploaded while command runs, before final flush; allowlist excludes raw secrets', async t => {
  const f = await fixture(t); await f.append(event()); let tick = 0;
  f.options.wait = async () => {
    assert.equal(f.uploads.length, 1); assert.equal(f.uploads[0].packet.status, 'running');
    assert(!JSON.stringify(f.uploads).includes('SECRET'));
    tick++; await f.append(event(2)); await f.done();
  };
  assert.deepEqual(await publish(f.options), {uploadedPackets: 2, observedFailures: 2});
  const complete = JSON.parse(await readFile(path.join(f.stateDir, 'complete.json')));
  assert.equal(complete.status, 'monitoring_complete');
  assert.equal(complete.observer_status, 'failed');
  assert.equal(tick, 1); assert.notEqual(f.uploads[0].name, f.uploads[1].name);
  assert.match(f.uploads[0].name, /^failure-123-api26-a2-12345678/);
  assert.equal(f.uploads[1].packet.ended_utc, '2026-10-25T18:00:05.000Z');
});
test('partial UTF8 JSONL waits for newline, then immediate upload', async t => {
  const f = await fixture(t), line = JSON.stringify(event());
  await f.append(line.slice(0, 90)); let ticks = 0;
  f.options.wait = async () => {
    if (ticks++ === 0) { assert.equal(f.uploads.length, 0); await f.append(line.slice(90) + '\n'); }
    else { assert.equal(f.uploads.length, 1); await f.manifest({...running, status: 'failed', observed_failure_lines: 1, ended_utc: '2026-10-25T18:00:05.000Z'}); }
  };
  await publish(f.options); assert.equal(f.uploads.length, 1);
});
test('subsequent batch size is bounded and final remainder flushes', async t => {
  const f = await fixture(t); for (let i = 1; i <= 6; i++) await f.append(event(i));
  await f.done(); f.options.batchSize = 2;
  await publish(f.options); assert.deepEqual(f.uploads.map(x => x.packet.failures.length), [1, 2, 2, 1]);
});
test('timer batch publishes while command still running', async t => {
  const f = await fixture(t); await f.append(event()); let time = 0, tick = 0;
  f.options.now = () => time; f.options.batchMs = 100;
  f.options.wait = async () => {
    if (tick++ === 0) { await f.append(event(2)); time = 101; }
    else { assert.equal(f.uploads.length, 2); assert.equal(f.uploads[1].packet.status, 'running'); await f.done(); }
  };
  await publish(f.options);
});
test('cap preserves first upload and pending packet with explicit degraded status', async t => {
  const f = await fixture(t); await f.append(event()); await f.append(event(2)); await f.done();
  f.options.maxArtifacts = 1;
  await assert.rejects(publish(f.options), /artifact_cap/);
  assert.equal(f.uploads.length, 1);
  const status = JSON.parse(await readFile(path.join(f.stateDir, 'monitoring-degraded.json')));
  assert.equal(status.pending_failures[0].observation_index, 2);
  assert.equal(JSON.parse(await readFile(path.join(f.stateDir, 'packet-1.json'))).failures[0].observation_index, 1);
  assert.equal(JSON.parse(await readFile(path.join(f.stateDir, 'packet-2.json'))).failures[0].observation_index, 2);
});
test('uncertain SDK failure retains packet without leaking error, retry or duplicate', async t => {
  const f = await fixture(t); await f.append(event()); await f.done(); let calls = 0;
  f.options.client.uploadArtifact = async () => { calls++; throw new Error('SECRET_TOKEN'); };
  await assert.rejects(publish(f.options), /upload_uncertain/);
  await assert.rejects(publish(f.options), {code: 'EEXIST'}); assert.equal(calls, 1);
  assert(!(await readFile(path.join(f.stateDir, 'monitoring-degraded.json'), 'utf8')).includes('SECRET_TOKEN'));
});
test('completed session refuses restart rather than duplicate immutable uploads', async t => {
  const f = await fixture(t); await f.append(event()); await f.done(); await publish(f.options);
  await assert.rejects(publish(f.options), {code: 'EEXIST'}); assert.equal(f.uploads.length, 1);
});
test('cancellation drains and flushes without supervising the test process', async t => {
  const f = await fixture(t), controller = new AbortController();
  await f.append(event()); await f.append(event(2)); controller.abort(); f.options.signal = controller.signal;
  await assert.rejects(publish(f.options), /publisher_cancelled/); assert.equal(f.uploads.length, 2);
});
test('terminal incomplete line is degraded, not silently discarded', async t => {
  const f = await fixture(t); await f.append(event()); await f.append('{'); await f.done();
  await assert.rejects(publish(f.options), /partial_final_line/); assert.equal(f.uploads.length, 1);
});
test('oversize evidence, out of order events and mismatched source fail closed', async t => {
  for (const line of ['x'.repeat(17000), JSON.stringify(event(2)) + '\n', JSON.stringify({...event(), sha: 'b'.repeat(40)}) + '\n']) {
    const f = await fixture(t); await f.append(line); await f.done();
    await assert.rejects(publish(f.options), /invalid_evidence/); assert.equal(f.uploads.length, 0);
  }
});
test('untrusted identifiers cannot become traversal, annotations, or arbitrary context', () => {
  for (const changes of [{api: '../26'}, {lane: 'a\n::error::'}, {class: '../secret'}, {method: 'x\n'}, {display_name: 'fails[https://secret]'}])
    assert.throws(() => validateEvent({...event(), ...changes}, running));
  assert.equal(validateEvent(event(), running).context, 'example.SomeTest > fails[emulator-5554] FAILED');
});
test('zero failures completes without inventing a pass or creating an artifact', async t => {
  const f = await fixture(t); await f.done();
  assert.deepEqual(await publish(f.options), {uploadedPackets: 0, observedFailures: 0});
  assert.deepEqual((await readdir(f.stateDir)).sort(), ['complete.json']);
});

test('terminal count mismatch cannot report monitoring complete', async t => {
  const f = await fixture(t);
  await f.manifest({...running, status: 'failed', observed_failure_lines: 1, ended_utc: '2026-10-25T18:00:05.000Z'});
  await assert.rejects(publish(f.options), /invalid_evidence/);
});
test('append-file replacement is detected', async t => {
  const f = await fixture(t); await f.append(event());
  f.options.wait = async () => {
    const file = path.join(f.options.evidenceDir, 'events.jsonl');
    // Keep old inode allocated while replacing the pathname.
    const {rename} = await import('node:fs/promises');
    await rename(file, file + '.old'); await writeFile(file, JSON.stringify(event(2)) + '\n');
  };
  await assert.rejects(publish(f.options), /invalid_evidence/);
  assert.equal(f.uploads.length, 1);
});
test('waiting for observer creation is cancellable', async t => {
  const f = await fixture(t), controller = new AbortController();
  await rm(f.options.evidenceDir, {recursive: true});
  f.options.signal = controller.signal; f.options.wait = async () => controller.abort();
  await assert.rejects(publish(f.options), /publisher_cancelled/);
});

test('observer startup failure is bounded without an external cancellation', async t => {
  const f = await fixture(t); await rm(f.options.evidenceDir, {recursive: true});
  let time = 0;
  f.options.now = () => time; f.options.startupMs = 20;
  f.options.wait = async () => { time += 10; };
  await assert.rejects(publish(f.options), /observer_startup_timeout/);
  assert.equal(time, 20); assert.equal(f.uploads.length, 0);
  const status = JSON.parse(await readFile(path.join(f.stateDir, 'monitoring-degraded.json')));
  assert.equal(status.reason, 'observer_startup_timeout');
});
