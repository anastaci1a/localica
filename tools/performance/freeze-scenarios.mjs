import fs from 'node:fs';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {scenarioIds} from './none-contract.mjs';
const root=fileURLToPath(new URL('../../',import.meta.url));
const digest=data=>crypto.createHash('sha256').update(data).digest('hex');
const output=root+'tools/performance/scenarios.json';
if(process.argv[2]==='write') {
    const files=['app/src/androidTest/java/io/github/anastaci1a/localica/UiPerformanceTest.kt',
        'app/src/main/java/io/github/anastaci1a/localica/browse/BrowsePreferences.kt',
        'app/src/main/java/io/github/anastaci1a/localica/diagnostics/FrameStats.kt',
        'tools/performance/run-ui-benchmarks.sh','tools/performance/frametimeline.pbtxt',
        'tools/performance/none-contract.mjs','tools/performance/freeze-scenarios.mjs','tools/performance/summarize-ui.mjs'];
    const sourceFiles=Object.fromEntries(files.map(p=>[p,digest(fs.readFileSync(root+p))]));
    const manifest={schemaVersion:1,scriptVersion:'stage0-flat-none-v1',seed:600,tracks:2000,artwork:'placeholders; no media I/O',
        glassRequested:'FlatNone',glassEffective:'FlatNone',warmups:1,measuredRuns:3,
        viewports:{26:{width:1080,height:1920,dpi:420},36:{width:720,height:1280,dpi:280}},sourceFiles,
        actionHashDefinition:'SHA256(full UiPerformanceTest source hash + NUL + selected scenario ID); any harness change invalidates all scenarios',
        scenarios:scenarioIds.map(id=>({id,actionScriptSha256:digest(sourceFiles[files[0]]+'\0'+id),
            expectedWindows:['activity',...(['popup-open-close','back-during-popup-retract'].includes(id)?['modal']:[])],
            note:id==='queue-pull-during-player-collapse'?'Current behaviour: Player owns pull; Stage 2 (U10) decides whether Queue takeover should exist':
                id==='drag-start-end-in-list'?'One selected Queue row starts and ends inside the list':id==='search-typing'?'Inline search; system IME excluded':''}))};
    fs.writeFileSync(output,JSON.stringify(manifest,null,2)+'\n');
} else if(process.argv[2]==='check') {
    const manifest=JSON.parse(fs.readFileSync(output,'utf8'));
    if(manifest.scriptVersion!=='stage0-flat-none-v1' || manifest.glassRequested!=='FlatNone' || manifest.glassEffective!=='FlatNone')throw Error('Frozen protocol/renderer identity mismatch');
    if(JSON.stringify(manifest.scenarios.map(s=>s.id))!==JSON.stringify(scenarioIds))throw Error('Scenario census changed');
    for(const [p,hash]of Object.entries(manifest.sourceFiles))if(digest(fs.readFileSync(root+p))!==hash)throw Error('Frozen script changed: '+p);
} else throw Error('Use write once after review, then check before every capture');
