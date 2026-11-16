import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
const repo = path.resolve(import.meta.dirname, '../../..');
test('Protocol covers the eleven cost-map scenarios with original input sources', () => {
    const protocol = JSON.parse(fs.readFileSync(path.join(import.meta.dirname, 'scenarios.json')));
    assert.equal(protocol.tracks, 13400); assert.equal(protocol.queueEntries, 13400);
    assert.equal(new Set(protocol.scenarios.map(s => s.id)).size, 11);
    for (const [file, hash] of Object.entries({A35InputTimeline: '654e1a99b0df39554925cf7c7e2521d630112b2728d51de25ec87f8bbf1933fd',
        S20MultiTouch: 'd7b3599b56980c98765a93b4f73ae4014b44abbf5ec2b8c3f5e20b3d0425dd32'})) {
        assert.equal(createHash('sha256').update(fs.readFileSync(path.join(repo, 'benchmark/src/main/java', file + '.java'))).digest('hex'), hash);
    }
});
test('Promoted scale seeder creates valid operator-scale Room data and refuses overwrites', () => {
    const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'localica-986-fixture-'));
    try {
        const output = path.join(temp, 'fixture');
        const run = () => spawnSync(process.execPath, [path.join(import.meta.dirname, 'fixture.mjs'), output], {encoding: 'utf8'});
        const first = run(); assert.equal(first.status, 0, first.stderr);
        for (const [file, table] of [['library.db', 'tracks'], ['queues.db', 'queue_entries']]) {
            const db = new DatabaseSync(path.join(output, 'scale/databases', file), {readOnly: true});
            assert.equal(db.prepare('SELECT COUNT(*) n FROM ' + table).get().n, 13400);
            assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
            db.close();
        }
        assert.equal(createHash('sha256').update(fs.readFileSync(path.join(output, 'scale/files/949-scale/tone.mp3'))).digest('hex'),
            'd491c313c84749c2965da21dc20e7f3a10a78ec1ecb8a1ad46bc0134db3da5fe');
        assert.notEqual(run().status, 0);
    } finally { fs.rmSync(temp, {recursive: true}); }
});

test('Functional shard selection cannot silently omit or invent requested cases', () => {
    const run = selection => spawnSync(process.execPath, [path.join(import.meta.dirname, 'plan.mjs')],
        {encoding: 'utf8', env: {...process.env, GITHUB_OUTPUT: '', PERFORMANCE_SCENARIOS: selection}});
    const all = run(''); assert.equal(all.status, 0, all.stderr);
    assert.equal(JSON.parse(all.stdout.trim().slice(7)).scenario.length, 11);
    const one = run('["cold-start"]'); assert.equal(one.status, 0, one.stderr);
    assert.deepEqual(JSON.parse(one.stdout.trim().slice(7)).scenario, ['cold-start']);
    for (const invalid of ['[]', '["missing"]', '["cold-start","cold-start"]']) assert.notEqual(run(invalid).status, 0);
    const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'localica-986-matrix-'));
    try {
        const output = path.join(temp, 'output');
        const file = spawnSync(process.execPath, [path.join(import.meta.dirname, 'plan.mjs')],
            {env: {...process.env, GITHUB_OUTPUT: output, PERFORMANCE_SCENARIOS: '["cold-start"]'}});
        assert.equal(file.status, 0);
        const text = fs.readFileSync(output, 'utf8');
        assert(text.endsWith('\n'));
        assert.deepEqual(JSON.parse(text.trim().slice(7)).scenario, ['cold-start']);
    } finally { fs.rmSync(temp, {recursive: true}); }

});
