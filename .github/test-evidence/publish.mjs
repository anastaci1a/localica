#!/usr/bin/env node
/** Live provisional failure evidence; never a test verdict or process supervisor.
 * Run under the emulator JS action so the official SDK inherits its normal runtime
 * environment. No credential reading, copying, printing, or extra permissions.
 * CLI: --evidence-dir OBSERVER_OUTPUT --state-dir NEW_DIRECTORY --run-id ID --attempt N
 * Terminal observer manifest (written after events close) triggers final drain.
 * Missing initial manifest degrades after 30s; parent must stop the publisher if
 * the observer dies after startup without writing a terminal manifest.
 * SIGINT/SIGTERM flush available complete lines, then return monitoring-degraded.
 * Parent must preserve test exit and separately gate on publisher/observer status.
 *
 * Six immutable artifacts maximum leaves four of the documented ten/job for other
 * uploads (https://github.com/actions/toolkit/blob/main/packages/artifact/README.md).
 * First failure uploads immediately; one optional first-failure context packet follows.
 * Both share the six-artifact cap; subsequent batches <=32 failures every 15s.
 * At cap/error, pending packet remains on disk and monitoring-degraded.json records
 * the unread byte offset. Nothing is reported as fully monitored. No blind retry:
 * existing state directories are refused, including uncertain completed uploads.
 * Packets contain only validated identifiers and a reconstructed failure-line
 * context, plus bounded first-failure exception types/frames (no messages), never argv, environment, raw logs, manifest errors, media or XML verdicts.
 */
import {mkdir, open, readFile, rename, stat} from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';

const MAX_LINE = 16 * 1024;
const MAX_MANIFEST = 256 * 1024;
const CHUNK = 16 * 1024;
const TERMINAL = new Set(['completed', 'failed', 'cancelled', 'incomplete']);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok) { if (!ok) throw new Error('invalid_evidence'); }
function text(value, pattern, max = 256) {
  check(typeof value === 'string' && value.length <= max && !/[\u0000-\u001f\u007f]/.test(value) && pattern.test(value));
  return value;
}
function integer(value, min = 0) { check(Number.isSafeInteger(value) && value >= min); return value; }
function utc(value) { return text(value, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z$/, 32); }
function identity(value) {
  return {
    command_id: text(value.command_id, /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/),
    sha: text(value.sha, /^[a-f0-9]{40}$/),
    api: text(value.api, /^\d{1,3}$/),
    lane: text(value.lane, /^[a-zA-Z0-9_-]{1,40}$/),
  };
}
export function validateEvent(value, manifest) {
  check(value && value.event === 'test_failure_observed' && value.provisional === true);
  const ids = identity(value);
  for (const key of Object.keys(ids)) check(ids[key] === manifest[key]);
  const className = text(value.class, /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+$/, 512);
  const method = text(value.method, /^[\w$ .()-]+$/, 512);
  const display = text(value.display_name, /^[\w$ .()\[\],:+-]+$/, 768);
  check(display.split('[', 1)[0].trimEnd() === method);
  check(value.stream === 'stdout' || value.stream === 'stderr');
  return {...ids, event: value.event, provisional: true, utc: utc(value.utc),
    observation_index: integer(value.observation_index, 1), stream: value.stream,
    stream_line: integer(value.stream_line, 1), stream_byte_offset: integer(value.stream_byte_offset),
    class: className, method, display_name: display,
    // Bounded reconstructed matched-line context, not arbitrary surrounding output.
    context: `${className} > ${display} FAILED`};
}
export function validateContext(value, manifest, firstFailure) {
  check(value?.event === 'test_failure_context' && value.provisional === true);
  const ids = identity(value);
  for (const key of Object.keys(ids)) check(ids[key] === manifest[key]);
  check(firstFailure && value.observation_index === 1 && value.stream === firstFailure.stream);
  check(value.messages_omitted === true);
  check(['boundary', 'eof', 'limit', 'timeout'].includes(value.end_reason));
  check(Array.isArray(value.entries) && value.entries.length > 0 && value.entries.length <= 32);
  let bytes = 0;
  const entries = value.entries.map(entry => {
    check(entry && ['exception_type', 'frame'].includes(entry.kind));
    const pattern = entry.kind === 'exception_type'
      ? /^(?:[A-Za-z_$][\w$]*\.)+(?:[A-Za-z_$][\w$]*)?(?:Error|Exception)$/
      : /^(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$<>][\w$<>]*\((?:[A-Za-z_$][\w$]*\.(?:java|kt):[0-9]{1,7}|Native Method|Unknown Source)\)$/;
    const clean = text(entry.value, pattern, 4096);
    bytes += Buffer.byteLength(clean); check(bytes <= 4096);
    return {kind: entry.kind, value: clean};
  });
  return {...ids, event: value.event, utc: utc(value.utc), provisional: true,
    observation_index: 1, stream: value.stream, messages_omitted: true,
    end_reason: value.end_reason, entries};
}

async function atomicJson(file, value) {
  const temp = `${file}.tmp`;
  const handle = await open(temp, 'wx', 0o600);
  try { await handle.writeFile(JSON.stringify(value, null, 2) + '\n'); await handle.sync(); }
  finally { await handle.close(); }
  await rename(temp, file);
}
async function manifestAt(directory) {
  const file = path.join(directory, 'manifest.json');
  try {
    check((await stat(file)).size <= MAX_MANIFEST);
    const value = JSON.parse(await readFile(file, 'utf8'));
    check(value.schema_version === 1 && (value.status === 'running' || TERMINAL.has(value.status)));
    return {...identity(value), started_utc: utc(value.started_utc),
      ended_utc: value.ended_utc === null ? null : utc(value.ended_utc), status: value.status,
      observed_failure_lines: integer(value.observed_failure_lines)};
  } catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}

export async function publish({evidenceDir, stateDir, runId, attempt, client,
  pollMs = 250, batchMs = 15000, batchSize = 32, maxArtifacts = 6, startupMs = 30000,
  signal, now = Date.now, wait = sleep}) {
  text(runId, /^\d{1,24}$/); text(attempt, /^\d{1,8}$/);
  integer(batchSize, 1); check(batchSize <= 32);
  integer(maxArtifacts, 1); check(maxArtifacts <= 6);
  integer(startupMs, 1); check(startupMs <= 30000);
  const startupAt = now();
  // Exclusive ownership is the restart/idempotency policy. Do not remove this guard.
  await mkdir(stateDir, {recursive: false, mode: 0o700});
  let offset = 0, partial = Buffer.alloc(0), sequence = 0, lastIndex = 0;
  let pending = [], contexts = [], firstFailure, contextSeen = false, manifest, lastUpload = now(), sourceIdentity;
  let reason = 'invalid_evidence';
  const status = extra => ({schema_version: 1, status: 'monitoring_degraded',
    run_id: runId, attempt, uploaded_packets: sequence, read_offset: offset,
    partial_line_bytes: partial.length, last_observation_index: lastIndex, ...extra});
  async function flush() {
    if (!pending.length && !contexts.length) return;
    const {observed_failure_lines: snapshotCount, ...manifestIdentityAndTimes} = manifest;
    const packet = {schema_version: 1, run_id: runId, attempt, ...manifestIdentityAndTimes,
      observer_manifest_snapshot_count: snapshotCount,
      publisher_observed_failure_lines: lastIndex,
      observations_provisional: true, test_verdict: 'not_determined',
      sequence: sequence + 1, failures: pending,
      ...(contexts.length ? {failure_contexts: contexts} : {})};
    const digest = createHash('sha256').update(JSON.stringify(packet, null, 2) + '\n').digest('hex');
    const name = `failure-${runId}-api${manifest.api}-a${attempt}-${manifest.command_id}-${sequence + 1}-${digest.slice(0, 16)}`;
    const file = path.join(stateDir, `packet-${sequence + 1}.json`);
    await atomicJson(file, packet);
    if (sequence >= maxArtifacts) { reason = 'artifact_cap'; throw new Error(reason); }
    reason = 'upload_uncertain';
    const result = await client.uploadArtifact(name, [file], path.resolve(stateDir),
      {retentionDays: 7, compressionLevel: 0});
    check(Number.isSafeInteger(result.id) && result.id > 0);
    sequence++;
    await atomicJson(path.join(stateDir, `receipt-${sequence}.json`),
      {name, artifact_id: result.id, packet_sha256: digest, sequence});
    pending = []; contexts = []; lastUpload = now(); reason = 'invalid_evidence';
  }
  async function consume(line) {
    check(line.length > 0 && line.length <= MAX_LINE);
    const raw = JSON.parse(line.toString('utf8'));
    if (raw.event === 'test_failure_context') {
      check(!contextSeen);
      contexts.push(validateContext(raw, manifest, firstFailure));
      contextSeen = true;
      // First alert is already uploaded. A separate immutable context packet follows
      // promptly, without waiting for test termination or the normal batch interval.
      await flush();
      return;
    }
    const event = validateEvent(raw, manifest);
    check(event.observation_index === lastIndex + 1);
    if (!firstFailure) firstFailure = event;
    pending.push(event); lastIndex = event.observation_index;
    if (sequence === 0 || pending.length >= batchSize) await flush();
  }
  async function drain() {
    let file;
    try { file = await open(path.join(evidenceDir, 'events.jsonl'), 'r'); }
    catch (error) { if (error.code === 'ENOENT' && !TERMINAL.has(manifest.status)) return; throw error; }
    try {
      const info = await file.stat();
      const currentIdentity = `${info.dev}:${info.ino}`;
      check(!sourceIdentity || sourceIdentity === currentIdentity);
      sourceIdentity = currentIdentity;
      check(info.size >= offset);
      // Snapshot the append-only length; a continuously noisy producer cannot starve polling.
      while (offset < info.size) {
        const buffer = Buffer.alloc(Math.min(CHUNK, info.size - offset));
        const {bytesRead} = await file.read(buffer, 0, buffer.length, offset);
        check(bytesRead > 0);
        let start = 0;
        while (start < bytesRead) {
          const newline = buffer.indexOf(10, start);
          const end = newline < 0 || newline >= bytesRead ? bytesRead : newline;
          const fragment = buffer.subarray(start, end);
          check(partial.length + fragment.length <= MAX_LINE);
          partial = Buffer.concat([partial, fragment]);
          offset += fragment.length;
          if (end < bytesRead) {
            offset++;
            const line = partial; partial = Buffer.alloc(0); await consume(line);
          }
          start = end + 1;
        }
      }
    } finally { await file.close(); }
  }
  try {
    while (true) {
      const latest = await manifestAt(evidenceDir);
      if (latest) {
        if (manifest) for (const key of ['command_id', 'sha', 'api', 'lane', 'started_utc']) check(latest[key] === manifest[key]);
        manifest = latest;
        await drain();
        if (signal?.aborted || TERMINAL.has(manifest.status)) {
          if (partial.length) { reason = 'partial_final_line'; throw new Error(reason); }
          if (TERMINAL.has(manifest.status)) {
            check(manifest.ended_utc !== null);
            check(manifest.observed_failure_lines === lastIndex);
          }
          await flush();
          if (signal?.aborted) { reason = 'publisher_cancelled'; throw new Error(reason); }
          if (manifest.status === 'incomplete' || manifest.status === 'cancelled') {
            reason = 'observer_incomplete'; throw new Error(reason);
          }
          await atomicJson(path.join(stateDir, 'complete.json'),
            {schema_version: 1, ...manifest, observer_status: manifest.status,
              status: 'monitoring_complete', uploaded_packets: sequence,
              observed_failures: lastIndex, read_offset: offset});
          return {uploadedPackets: sequence, observedFailures: lastIndex};
        }
        if (pending.length && now() - lastUpload >= batchMs) await flush();
      } else if (signal?.aborted) { reason = 'publisher_cancelled'; throw new Error(reason); }
      else if (now() - startupAt >= startupMs) { reason = 'observer_startup_timeout'; throw new Error(reason); }
      await wait(pollMs);
    }
  } catch {
    // No raw exception: SDK/network errors may contain URLs or sensitive headers.
    await atomicJson(path.join(stateDir, 'monitoring-degraded.json'), status({reason,
      pending_failures: pending, pending_contexts: contexts, manifest: manifest ?? null}));
    throw new Error(`monitoring-degraded: ${reason}`);
  }
}

async function main() {
  const args = process.argv.slice(2), options = {};
  const keys = {'--evidence-dir': 'evidenceDir', '--state-dir': 'stateDir', '--run-id': 'runId', '--attempt': 'attempt'};
  check(args.length === 8);
  for (let i = 0; i < args.length; i += 2) {
    const key = keys[args[i]]; check(key && !options[key] && args[i + 1]); options[key] = args[i + 1];
  }
  const controller = new AbortController();
  for (const name of ['SIGINT', 'SIGTERM']) process.on(name, () => controller.abort());
  const {DefaultArtifactClient} = await import('@actions/artifact');
  await publish({...options, client: new DefaultArtifactClient(), signal: controller.signal});
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(() => { console.error('monitoring-degraded: publisher failed; inspect local publisher status, preserve test exit'); process.exitCode = 1; });
}

