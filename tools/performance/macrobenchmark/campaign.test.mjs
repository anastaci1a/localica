import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {protocol, metricsFor} from './ratchet.mjs';

test('Packing requires raw health, recording and background receipts for every qualified sample', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'localica-campaign-'));
    const write = (file, data) => fs.writeFileSync(file, typeof data === 'string' ? data : JSON.stringify(data));
    try {
        const identity = {runId: 'test', sourceSha: 'a'.repeat(40), apkSha256: 'b'.repeat(64), fixtureSha256: 'fixture'};
        write(root + '/identity.json', identity);
        for (const {id} of protocol.scenarios) for (let i = 1; i <= 5; i++) {
            const dir = root + '/' + id + '/run-' + i;
            fs.mkdirSync(dir, {recursive: true});
            write(dir + '/metrics.json', {qualified: true, arm: 'plain', compilation: 'speed-profile',
                fixture: {tracks: 13400, queueEntries: 13400, tarSha256: identity.fixtureSha256}, frames: 10,
                source: identity.sourceSha, apk: {sha256: identity.apkSha256},
                ...Object.fromEntries(metricsFor(id).map(key => [key, 1]))});
            for (const phase of ['pre', 'post']) write(dir + '/' + phase + '.json', {rejections: []});
            write(dir + '/screenrecord.txt', '');
            write(dir + '/background-qualification.json', {rejected: []});
        }
        const run = () => spawnSync(process.execPath, [new URL('./campaign.mjs', import.meta.url).pathname,
            'pack', root, root + '/identity.json', root + '/result.json'], {encoding: 'utf8'});
        assert.equal(run().status, 0);
        const target = root + '/library-list-fling/run-1/';
        for (const [name, bad] of [['post.json', {rejections: ['thermal']}], ['screenrecord.txt', '123'],
            ['background-qualification.json', {rejected: ['busy']}], ['metrics.json', {qualified: true}]]) {
            fs.unlinkSync(root + '/result.json');
            const original = fs.readFileSync(target + name);
            write(target + name, bad);
            assert.notEqual(run().status, 0, name);
            fs.writeFileSync(target + name, original);
            assert.equal(run().status, 0);
        }
    } finally { fs.rmSync(root, {recursive: true, force: true}); }
});
