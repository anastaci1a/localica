// Consume the accepted #971 plain runner's text metrics; never substitute emulator JSON.
import fs from 'node:fs';
import path from 'node:path';
import {median, metricsFor, protocol} from './ratchet.mjs';
const [mode, directory, identityFile, output] = process.argv.slice(2);
if (!['pack', 'anchor'].includes(mode) || !output) throw new Error('Use pack|anchor RUN_DIRECTORY IDENTITY_JSON OUTPUT_JSON');
if (fs.existsSync(output)) throw new Error('Refuse to overwrite an accepted campaign or anchor');
const identity = JSON.parse(fs.readFileSync(identityFile));
const surfaces = {}, inventory = [];
for (const scenario of protocol.scenarios) {
    const dir = path.join(directory, scenario.id), runs = [];
    for (const name of fs.readdirSync(dir).filter(name => /^run-\d+$/.test(name)).sort()) {
        const runDir = path.join(dir, name), raw = JSON.parse(fs.readFileSync(path.join(runDir, 'metrics.json')));
        inventory.push({surface: scenario.id, run: name, qualified: raw.qualified, error: raw.error});
        if (raw.qualified !== true) continue;
        if (raw.arm !== 'plain' || raw.compilation !== 'speed-profile' || raw.fixture?.tracks !== 13400 ||
            raw.fixture?.queueEntries !== 13400 || raw.error || !(raw.frames > 0)) throw new Error('Invalid plain runner qualification: ' + dir + '/' + name);
        if (!identity.runId || !/^[a-f0-9]{40}$/.test(identity.sourceSha) || !/^[a-f0-9]{64}$/.test(identity.apkSha256) ||
            raw.source !== identity.sourceSha || raw.apk?.sha256 !== identity.apkSha256 || raw.fixture.tarSha256 !== identity.fixtureSha256)
            throw new Error('Source, APK or fixture receipt mismatch: ' + runDir);
        for (const phase of ['pre', 'post']) if (JSON.parse(fs.readFileSync(path.join(runDir, phase + '.json'))).rejections.length)
            throw new Error('Rejected device health: ' + runDir);
        if (fs.readFileSync(path.join(runDir, 'screenrecord.txt'), 'utf8').trim() ||
            JSON.parse(fs.readFileSync(path.join(runDir, 'background-qualification.json'))).rejected.length)
            throw new Error('Recording or background contamination: ' + runDir);
        const selected = metricsFor(scenario.id);
        const values = Object.fromEntries(selected.map(key => [key, raw[key]]));
        for (const value of Object.values(values)) if (!Number.isFinite(value) || value < 0) throw new Error('Missing metric: ' + dir + '/' + name);
        runs.push({qualified: true, metrics: values});
    }
    if (runs.length < 5) throw new Error('Need five qualified runs: ' + scenario.id);
    surfaces[scenario.id] = runs;
}
const campaign = {...identity, qualification: 'plain', compilation: 'speed-profile', screenrecord: false, surfaces, inventory};
const result = mode === 'pack' ? campaign : {...identity, bars: Object.fromEntries(Object.entries(surfaces).map(([surface, runs]) =>
    [surface, Object.fromEntries(Object.keys(runs[0].metrics).map(metric => {
        const values = runs.map(run => run.metrics[metric]);
        return [metric, {median: median(values), tolerance: Math.max(...values) - Math.min(...values)}];
    }))]))};
fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
