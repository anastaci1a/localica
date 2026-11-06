import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {pathToFileURL} from 'node:url';
export const scenarioIds = ['list-smallest','list-small','list-tracklist','list-compact','list-normal','list-large',
    'gallery-1','gallery-2','gallery-3','gallery-4','gallery-5','subview-scroll','carousel','artist-album-carousel',
    'queue-drawer','player-expand-collapse','popup-open-close','lyrics-open-close','lyrics-content-theme',
    'search-typing','pinch-sizes','multi-select-drag','interrupted-player-drag','back-during-popup-retract',
    'queue-pull-during-player-collapse','drag-start-end-in-list'];
const assert = (ok, why) => { if (!ok) throw Error(why); };
export function validate(runs, source, expected = scenarioIds) {
    assert(/^[a-f0-9]{40}$/.test(source), 'Full source SHA required');
    assert(expected.length && new Set(expected).size === expected.length && expected.every(s=>scenarioIds.includes(s)), 'Unknown or empty scenario selection');
    assert(runs.length === expected.length*4, 'Expected one warmup and three measurements per scenario');
    const api = runs[0]?.api; assert([26,36].includes(api), 'Unsupported API');
    for (const name of expected) {
        const selected = runs.filter(r => r.scenario === name);
        assert(selected.length === 4 && [0,1,2,3].every(i => selected.filter(r=>r.rep===i).length===1), `Missing/duplicate repetition ${name}`);
        for (const r of selected) {
            assert(r.warmup === (r.rep===0) && r.source === source && r.buildSource === source && r.api === api, 'Source, API or warmup mismatch');
            assert(r.requestedGlass === 'None' && r.glassMode === 'None' && r.effectiveGlass === 'None' && r.observedSceneBackend === 'None' && r.glassFallback === false, 'Requested/effective None mismatch');
            assert(r.protocolLegacy===false, 'Pre-tag recorder comparison cannot become the authoritative baseline');
            assert(r.seed===600 && r.songs===2000 && r.scriptVersion==='stage0-v1' && r.scenarioTag===name, 'Fixture/script/tag mismatch');
            assert(r.schema===2 && typeof r.captureId==='string' && r.captureId.length>0, 'Capture identity/schema');
            assert(!r.recording && !r.windowLimitReached && !r.timeLimitReached && r.windows?.length, 'Incomplete capture');
            assert(new Set(r.windows.map(w=>w.window)).size===r.windows.length, 'Duplicate window');
            if (['popup-open-close','back-during-popup-retract'].includes(name)) assert(r.windows.length>=2, 'Missing modal window');
            for (const w of r.windows) {
                assert(w.windowId===r.captureId+':'+w.window && Number.isInteger(w.hostEpoch) && w.hostEpoch>=0, 'Window identity/epoch');
                assert(w.frames>0 && !w.overflow && !w.invalid && !w.listenerDrops, 'Empty/invalid/lost window frames');
                assert(['p50Ms','p90Ms','p99Ms','jankPercent'].every(k=>typeof w[k]==='number' && Number.isFinite(w[k]) && w[k]>=0), 'Invalid distribution');
                assert(w.frameSamples?.length===w.frames && w.frameSamples.every(f=>f.length===4 && f[1]>=0 && f[2]>0), 'Missing raw samples');
                const durations=w.frameSamples.map(f=>f[1]).sort((a,b)=>a-b);
                for (const [key,p] of [['p50Ms',.5],['p90Ms',.9],['p99Ms',.99]])
                    assert(Math.abs(w[key]-durations[Math.ceil(p*w.frames)-1]/1e6)<1e-6, 'Raw percentile mismatch');
                assert(w.refreshPeriodsNanos?.length && w.refreshPeriodsNanos.every(n=>n>0), 'Missing refresh');
                if (api===26) assert(w.deadlineMisses===null && w.deadlineSamples===0, 'API26 deadline unavailable');
            }
            const milestones = (r.actions || []).map(a=>a.milestone);
            const required = {'interrupted-player-drag':['player-drag-reversed','semantic-geometry-settled'],
                'back-during-popup-retract':['first-back-dismiss','second-back-during-retained-window'],
                'queue-pull-during-player-collapse':['upward-pull-routed-to-player','semantic-geometry-settled'],
                'drag-start-end-in-list':['list-drag-retired']}[name] || [];
            assert(required.every(m=>milestones.includes(m)), 'Missing adversarial state witness');
        }
    }
    return {schemaVersion:1,status:'accepted-emulator-app-work',sourceSha:source,api,scenarios:expected,repetitions:3,warmups:1,
        presentationEvidence:'unavailable; FrameMetrics received app work only',nativeTail:'not acknowledged by FrameMetrics; no completeness/presentation claim'};
}
if (process.argv[1] && import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
    const [dir,source] = process.argv.slice(2);
    const files=fs.readdirSync(dir).filter(f=>/^[a-z0-9-]+-[0-3]-api(26|36)\.json$/.test(f));
    const runs=files.map(f=>JSON.parse(fs.readFileSync(path.join(dir,f),'utf8')));
    const result=validate(runs,source);
    result.scriptFreezeSha256=crypto.createHash('sha256').update(fs.readFileSync(path.join(dir,'script-freeze.json'))).digest('hex');
    result.installedApks=fs.readdirSync(dir).filter(f=>f.endsWith('-apk.sha256')).sort().map(f=>({path:f,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(dir,f))).digest('hex'),binding:fs.readFileSync(path.join(dir,f),'utf8').trim()}));
    result.artifacts=files.sort().map(f=>({path:f,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(dir,f))).digest('hex')}));
    fs.writeFileSync(path.join(dir,'none-receipt.json'),JSON.stringify(result,null,2)+'\n');
}
