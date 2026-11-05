import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

function fixture(run) {
    return { source: 'a'.repeat(40), api: 26, glassMode: 'Modern', seed: 600, songs: 2000,
        warmup: false, scenario: 'scroll', windows: [{ frames: 10, p50Ms: 1, p90Ms: 2, p99Ms: 3,
            jankPercent: 10, estimatedMissedVsyncs: 1, listenerDrops: 0, invalid: 0, overflow: 0 }], ...run };
}
function summarize(runs, check) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ui600-summary-'));
    try {
        runs.forEach((run, i) => fs.writeFileSync(path.join(directory, `${i}.json`), JSON.stringify(run)));
        const result = spawnSync(process.execPath, [new URL('./summarize-ui.mjs', import.meta.url).pathname, directory], { encoding: 'utf8' });
        check(result, directory);
    } finally { fs.rmSync(directory, { recursive: true, force: true }); }
}
test('warmup is excluded and worst-window repetition medians determine ranking', () => {
    const slow = fixture({}).windows[0];
    summarize([fixture({ warmup: true, scenario: 'warmup-only' }), fixture({}),
        fixture({ scenario: 'popup', windows: [slow, { ...slow, p99Ms: 20 }] }),
        fixture({ scenario: 'popup', windows: [slow, { ...slow, p99Ms: 10 }] })], (result, dir) => {
        assert.equal(result.status, 0, result.stderr);
        assert.equal(fs.readFileSync(path.join(dir, 'top-scenarios.txt'), 'utf8'), 'popup\nscroll\n');
        assert.match(result.stdout, /\| popup \| 2 \| 40 \| 1.00 \| 2.00 \| 15.00 \|/);
    });
});
test('mixed API or source cannot become one baseline', () => {
    for (const change of [{ api: 36 }, { source: 'b'.repeat(40) }])
        summarize([fixture({}), fixture(change)], result => assert.notEqual(result.status, 0));
});
test('overflow cannot silently produce an authoritative ranking', () => {
    const run = fixture({}); run.windows[0].overflow = 1;
    summarize([run], result => assert.notEqual(result.status, 0));
});
