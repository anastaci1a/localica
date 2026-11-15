import { test } from 'node:test';
import assert from 'node:assert/strict';
import { check, tighten } from './ratchet.mjs';
const identity = {protocolSha256: 'protocol', fixtureSha256: 'fixture', device: 'A35', environmentSha256: 'settings'};
const anchor = {...identity, bars: {list: {p50_ms: {median: 10, tolerance: 1}, gc_count: {median: 2, tolerance: 0}}}};
const candidate = (values, runId = 'first') => ({...identity, runId, qualification: 'plain', compilation: 'speed-profile', screenrecord: false,
    surfaces: {list: values.map(value => ({qualified: true, metrics: {p50_ms: value, gc_count: 2}}))}});
test('Uses the median of per-run percentiles and never recenters within noise', () => {
    const before = structuredClone(anchor);
    assert.equal(check(anchor, candidate([10.9, 10.9, 10.9, 10, 20])).pass, true);
    assert.equal(check(anchor, candidate([11.1, 11.1, 11.1, 10, 10])).pass, false);
    assert.deepEqual(anchor, before);
});
test('Rejects incomplete, unavailable, diagnostic and incomparable evidence', () => {
    for (const mutate of [c => c.surfaces.list.pop(), c => c.surfaces.list[0].qualified = false,
        c => c.surfaces.list[0].metrics.gc_count = undefined, c => c.fixtureSha256 = 'changed',
        c => c.qualification = 'profileable', c => c.compilation = 'speed', c => c.screenrecord = true]) {
        const c = candidate([10, 10, 10, 10, 10]); mutate(c); assert.throws(() => check(anchor, c));
    }
});
test('Tightens only after two independent improvements; bars and tolerances never increase', () => {
    const first = candidate([8, 8, 8, 8, 8]);
    assert.throws(() => tighten(anchor, first, first));
    const next = tighten(anchor, first, candidate([8.5, 8.5, 8.5, 8.5, 8.5], 'second'));
    assert.deepEqual(next.bars.list.p50_ms, {median: 8.5, tolerance: 0});
    assert.deepEqual(next.bars.list.gc_count, anchor.bars.list.gc_count);
    assert.deepEqual(tighten(anchor, first, candidate([9.5, 9.5, 9.5, 9.5, 9.5], 'second')), anchor);
});
