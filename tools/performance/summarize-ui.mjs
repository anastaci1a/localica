import fs from 'node:fs';
import path from 'node:path';

// Run against one device/source directory. Keep repetitions separate: never pool API levels.
const directory = process.argv[2];
if (!directory) throw new Error('Usage: node tools/performance/summarize-ui.mjs EVIDENCE_DIRECTORY');
const files = fs.readdirSync(directory).filter(name => name.endsWith('.json'));
const runs = files.map(name => ({ file: name, ...JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8')) }))
    .filter(run => run.scenario && !run.warmup);
if (!runs.length) throw new Error('No measured scenarios');
if (runs.some(run => run.windowLimitReached || run.timeLimitReached)) throw new Error('Capture limit reached');
for (const key of ['api', 'source', 'glassMode', 'songs', 'seed']) {
    if (new Set(runs.map(run => run[key])).size !== 1) throw new Error(`Mixed ${key}; use separate directories`);
}
const median = values => { const sorted = values.sort((a, b) => a - b); const n = sorted.length;
    return n % 2 ? sorted[(n - 1) / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2; };
const names = [...new Set(runs.map(run => run.scenario))];
const rows = names.map(name => {
    const selected = runs.filter(run => run.scenario === name);
    const windows = selected.flatMap(run => run.windows);
    if (windows.some(w => w.overflow || w.invalid)) throw new Error(`${name}: invalid or overflowed frame sample`);
    const worst = key => median(selected.map(run => Math.max(...run.windows.filter(w => w.frames > 0).map(w => w[key]))));
    return { name, repetitions: selected.length, p50: worst('p50Ms'), p90: worst('p90Ms'), p99: worst('p99Ms'),
        jank: worst('jankPercent'), missed: median(selected.map(r => r.windows.reduce((n, w) => n + w.estimatedMissedVsyncs, 0))),
        drops: windows.reduce((n, w) => n + w.listenerDrops, 0), frames: windows.reduce((n, w) => n + w.frames, 0) };
}).sort((a, b) => b.p99 - a.p99);
const format = n => n.toFixed(2);
const first = runs[0];
const report = `# UI frame baseline\n\nSource: ${first.source}. API ${first.api}; glass ${first.glassMode}; seed ${first.seed}; ${first.songs} songs.\n\n` +
    `Emulator proxy, debug build, placeholder artwork, no media I/O. Physical Galaxy A35 review remains required.\n\n` +
    `Ranked by median repetition p99 of the slowest Window. Each percentile column independently uses the slowest Window; these are not pooled-frame percentiles. Warmups excluded.\n\n` +
    `Jank means frame work exceeds the sampled display refresh period. Missed vsyncs are duration-based estimates, not presented-frame or idle-gap counts. Listener loss and incomplete native tails limit the received sample.\n\n` +
    `| Scenario | Reps | Frames | p50 ms | p90 ms | p99 ms | Jank % | Missed estimate | Listener drops |\n| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n` +
    rows.map(r => `| ${r.name} | ${r.repetitions} | ${r.frames} | ${format(r.p50)} | ${format(r.p90)} | ${format(r.p99)} | ${format(r.jank)} | ${r.missed} | ${r.drops} |`).join('\n') + '\n';
fs.writeFileSync(path.join(directory, 'report.md'), report);
fs.writeFileSync(path.join(directory, 'top-scenarios.txt'), rows.slice(0, 3).map(r => r.name).join('\n') + '\n');
console.log(report);
