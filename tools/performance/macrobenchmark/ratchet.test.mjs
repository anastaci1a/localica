import { test } from 'node:test';
import assert from 'node:assert/strict';
import { check, tighten, protocol, metricsFor } from './ratchet.mjs';
const identity = {protocolSha256: 'protocol', fixtureSha256: 'fixture', device: 'A35', environmentSha256: 'settings'};
const anchor = {...identity, bars: Object.fromEntries(protocol.scenarios.map(({id}) => [id, Object.fromEntries(metricsFor(id).map(key => [key, {median: key === 'gc_count' ? 2 : 10, tolerance: key === 'gc_count' ? 0 : 1}]))]))};
const candidate = (values, runId = 'first') => ({...identity, runId, qualification: 'plain', compilation: 'speed-profile', screenrecord: false,
    sourceSha: 'a'.repeat(40), apkSha256: 'b'.repeat(64),
    surfaces: Object.fromEntries(protocol.scenarios.map(({id}) => [id, values.map(value => ({qualified: true, metrics: Object.fromEntries(metricsFor(id).map(key => [key, key === 'gc_count' ? 2 : value]))}))]))});
test('Uses the median of per-run percentiles and never recenters within noise', () => {
    const before = structuredClone(anchor);
    assert.equal(check(anchor, candidate([10.9, 10.9, 10.9, 10, 20])).pass, true);
    assert.equal(check(anchor, candidate([11.1, 11.1, 11.1, 10, 10])).pass, false);
    assert.deepEqual(anchor, before);
});
test('Rejects incomplete, unavailable, diagnostic and incomparable evidence', () => {
    for (const mutate of [c => c.surfaces['library-list-fling'].pop(), c => c.surfaces['library-list-fling'][0].qualified = false,
        c => c.surfaces['library-list-fling'][0].metrics.gc_count = undefined, c => c.fixtureSha256 = 'changed',
        c => c.qualification = 'profileable', c => c.compilation = 'speed', c => c.screenrecord = true,
        c => delete c.sourceSha, c => delete c.apkSha256, c => delete c.runId]) {
        const c = candidate([10, 10, 10, 10, 10]); mutate(c); assert.throws(() => check(anchor, c));
    }
});
test('Rejects an anchor omitting a scenario or required metric', () => {
    for (const mutate of [a => delete a.bars['cold-start'], a => delete a.bars['cold-start'].cold_start_first_frame_ms]) {
        const a = structuredClone(anchor); mutate(a); assert.throws(() => check(a, candidate([10, 10, 10, 10, 10])));
    }
});
test('Tightens only after two independent improvements; bars and tolerances never increase', () => {
    const first = candidate([8, 8, 8, 8, 8]);
    assert.throws(() => tighten(anchor, first, first));
    const next = tighten(anchor, first, candidate([8.5, 8.5, 8.5, 8.5, 8.5], 'second'));
    assert.deepEqual(next.bars['library-list-fling'].p50_ms, {median: 8.5, tolerance: 0});
    assert.deepEqual(next.bars['library-list-fling'].gc_count, anchor.bars['library-list-fling'].gc_count);
    assert.deepEqual(tighten(anchor, first, candidate([9.5, 9.5, 9.5, 9.5, 9.5], 'second')), anchor);
});
