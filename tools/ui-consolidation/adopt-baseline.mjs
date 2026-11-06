import {pathToFileURL} from 'node:url';
import {strictJson} from './strict-json.mjs';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {validate} from '../performance/none-contract.mjs';
const assert=(ok,why)=>{if(!ok)throw Error(why);};
const sha256=data=>crypto.createHash('sha256').update(data).digest('hex');
export function verifyArtifacts(root, artifacts) {
    const names=new Set();
    for(const a of artifacts) {
        assert(a.path && !path.isAbsolute(a.path) && !a.path.split('/').includes('..') && !names.has(a.path),'Invalid artifact path');
        names.add(a.path);assert(sha256(fs.readFileSync(path.join(root,a.path)))===a.sha256,'Artifact hash mismatch');
    }
}
const [pinPath,source,api26,api36,out]=process.argv.slice(2);
if(process.argv[1] && import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
    assert(pinPath && source && api26 && api36 && out,'Usage: PIN SOURCE API26_DIR API36_DIR OUTPUT');
    const pin=strictJson(fs.readFileSync(pinPath,'utf8'));
    assert(Object.keys(pin).sort().join()==='architectureSha256,artifacts,excludedPreexistingChangeShas,integratedInventorySha256,integratedSha,integratedTreeSha,inventoryRevision,inventorySha256,predecessorMainSha,resolvedToolchainDigest,schemaVersion,sourceBlobDigests,sourceFilesSha256' && pin.schemaVersion===1,'Pin schema');
    assert(/^[a-f0-9]{40}$/.test(source),'Full measurement SHA');
    execFileSync('git',['merge-base','--is-ancestor',pin.integratedSha,source]);
    const diff=execFileSync('git',['diff','--numstat',pin.integratedSha,source,'--','app/src/main'],{encoding:'utf8'}).trim();
    let net=0;
    for(const line of diff.split('\n').filter(Boolean)) {
        const [add,del,file]=line.split('\t');net+=+add-+del;
        assert(/\/(diagnostics\/FrameStats|ui\/DiagnosticsSettings)\.kt$/.test(file),'Unexpected Stage0 production delta');
    }
    assert(net<=55,'Stage0 production cap exceeded');
    const artifacts=[],receipts=[];
    for(const [api,dir] of [[26,api26],[36,api36]]) {
        const receipt=strictJson(fs.readFileSync(path.join(dir,'none-receipt.json'),'utf8'));
        assert(receipt.api===api && receipt.sourceSha===source,'Receipt source/API mismatch');
        verifyArtifacts(dir,receipt.artifacts); verifyArtifacts(dir,receipt.installedApks);
        assert(receipt.installedApks.length===2,'Installed APK census');
        const frozen=fs.readFileSync(path.join(dir,'script-freeze.json'));
        assert(sha256(frozen)===receipt.scriptFreezeSha256 && sha256(execFileSync('git',['show',source+':tools/performance/scenarios.json']))===receipt.scriptFreezeSha256,'Frozen script/source mismatch');
        const runs=receipt.artifacts.map(a=>strictJson(fs.readFileSync(path.join(dir,a.path),'utf8')));
        receipts.push(validate(runs,source));
        artifacts.push({path:path.join(dir,'none-receipt.json'),sha256:sha256(fs.readFileSync(path.join(dir,'none-receipt.json')))});
    }
    const report={schemaVersion:1,integratedPinSha:pin.integratedSha,measurementBaselineSha:source,
        stage0ProductionNet:net,status:'emulator-baseline-verified; A35 operator capture pending',receipts,artifacts,
        limitations:['A35 capture/FrameTimeline presentation approval remains external','API26 presentation/deadline unavailable',
            'FrameMetrics native tail is not acknowledged; emulator comparisons describe received app work only']};
    fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
}
