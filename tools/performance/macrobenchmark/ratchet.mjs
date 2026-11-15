import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

export function median(values) {
    if (values.length < 5 || values.some(value => !Number.isFinite(value) || value < 0)) throw new Error('Need at least five finite nonnegative samples');
    const sorted = [...values].sort((a, b) => a - b), middle = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

export function check(anchor, candidate) {
    for (const key of ['protocolSha256', 'fixtureSha256', 'device', 'environmentSha256']) {
        if (!anchor[key] || candidate[key] !== anchor[key]) throw new Error('Incomparable ' + key);
    }
    if (candidate.qualification !== 'plain' || candidate.compilation !== 'speed-profile' || candidate.screenrecord !== false)
        throw new Error('Only qualified plain speed-profile captures can pass');
    const results = [];
    for (const [surface, metrics] of Object.entries(anchor.bars)) {
        const runs = candidate.surfaces[surface];
        if (!runs || runs.some(run => run.qualified !== true)) throw new Error('Missing or unqualified surface: ' + surface);
        for (const [metric, bar] of Object.entries(metrics)) {
            if (!Number.isFinite(bar.median) || !Number.isFinite(bar.tolerance) || bar.median < 0 || bar.tolerance < 0)
                throw new Error('Invalid anchor: ' + surface + '/' + metric);
            const values = runs.map(run => run.metrics[metric]), value = median(values);
            results.push({surface, metric, median: value, anchor: bar.median, tolerance: bar.tolerance,
                pass: value <= bar.median + bar.tolerance,
                confirmedImprovement: value < bar.median - bar.tolerance,
                spread: Math.max(...values) - Math.min(...values)});
        }
    }
    if (!results.length) throw new Error('Empty ratchet');
    return {pass: results.every(result => result.pass), results};
}

export function tighten(anchor, first, second) {
    if (!first.runId || first.runId === second.runId) throw new Error('Two independent confirmations required');
    const a = check(anchor, first), b = check(anchor, second);
    if (!a.pass || !b.pass) throw new Error('Cannot tighten with a failing campaign');
    const next = structuredClone(anchor);
    for (let index = 0; index < a.results.length; index++) {
        const x = a.results[index], y = b.results[index];
        if (x.confirmedImprovement && y.confirmedImprovement) next.bars[x.surface][x.metric] = {
            median: Math.max(x.median, y.median), tolerance: Math.min(x.tolerance, Math.max(x.spread, y.spread))
        };
    }
    return next;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [command, anchorFile, firstFile, secondFile, outputFile] = process.argv.slice(2);
    const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
    const result = command === 'check' ? check(read(anchorFile), read(firstFile)) :
        command === 'tighten' ? tighten(read(anchorFile), read(firstFile), read(secondFile)) :
        (() => { throw new Error('Use check ANCHOR CANDIDATE OUTPUT or tighten ANCHOR FIRST SECOND OUTPUT'); })();
    fs.writeFileSync(command === 'check' ? secondFile : outputFile, JSON.stringify(result, null, 2) + '\n');
    if (result.pass === false) process.exitCode = 1;
}
