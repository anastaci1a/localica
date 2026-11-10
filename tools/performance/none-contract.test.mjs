import fs from 'node:fs';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validate,scenarioIds} from './none-contract.mjs';
const source='a'.repeat(40);
function fixtures() {return [0,1,2,3].map(rep=>({rep,warmup:rep===0,scenario:'list-smallest',source,buildSource:source,api:26,
    schema:2,protocolLegacy:false,captureId:'fixture',requestedGlass:'FlatNone',effectiveGlass:'FlatNone',glassMode:'FlatNone',observedSceneBackend:'FlatNone',glassFallback:false,
    seed:600,songs:2000,scriptVersion:'stage0-flat-none-v1',scenarioTag:'list-smallest',recording:false,
    windows:[{window:'activity-0',windowId:'fixture:activity-0',hostEpoch:0,frameSamples:[1,1,1,1,1,2,2,2,2,3].map((v,i)=>[i,v*1e6,16666667,null]),frames:10,invalid:0,overflow:0,listenerDrops:0,p50Ms:1,p90Ms:2,p99Ms:3,jankPercent:0,
        refreshPeriodsNanos:[16666667],deadlineMisses:null,deadlineSamples:0}]}));}
test('26 exact distinct IDs; complete fixture admitted',()=>{assert.equal(new Set(scenarioIds).size,26);assert.equal(validate(fixtures(),source,['list-smallest']).api,26);});
test('fallback, source mismatch, unknown selection, missing rep/window, loss and deadline zero rejected',()=>{
    for(const change of [r=>r[0].effectiveGlass='Normal',r=>r[1].requestedGlass='Modern',r=>r.pop(),r=>r[0].windows=[],
        r=>r[0].buildSource='b'.repeat(40),r=>r[0].windows[0].listenerDrops=1,r=>r[0].windows[0].deadlineMisses=0]) {
        const runs=fixtures();change(runs);assert.throws(()=>validate(runs,source,['list-smallest']));
    }
    assert.throws(()=>validate(fixtures(),source,[]));
    assert.throws(()=>validate(fixtures(),source));
});

test('production capture tags cover the entire frozen scenario census',()=>{
    const code=fs.readFileSync(new URL('../../app/src/main/java/io/github/anastaci1a/localica/diagnostics/FrameStats.kt',import.meta.url),'utf8');
    const declaration=code.slice(code.indexOf('val scenarioTags ='),code.indexOf(".split(',')"));
    const tags=[...declaration.matchAll(/"([^"]*)"/g)].map(m=>m[1]).join('').split(',');
    assert.deepEqual(tags,scenarioIds);
});


test('legacy optical None and mixed renderer protocols cannot enter a FlatNone receipt',()=>{
    for(const change of [r=>r[0].scriptVersion='stage0-v1',r=>r[0].observedSceneBackend='SingleToneBlur',
        r=>r[0].requestedGlass='None',r=>r[0].effectiveGlass='None']) {
        const runs=fixtures();change(runs);assert.throws(()=>validate(runs,source,['list-smallest']));
    }
});
