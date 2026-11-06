import {test} from 'node:test';
import assert from 'node:assert/strict';
import {xml,manifest,compare,rules} from '../check-ui-lint.mjs';
const incident = {rule:rules[0],identity:'old'};
test('new incident and equal-count substitution fail', () => {
    assert.equal(compare([incident],[incident,{...incident,identity:'new'}],0).accepted,false);
    assert.equal(compare([incident],[{...incident,identity:'new'}],0).accepted,false);
});
test('unchanged identity with line relocation passes; closure rejects debt', () => {
    assert.equal(compare([incident],[{...incident,originalLine:150}],0).accepted,true);
    assert.equal(compare([incident],[incident],6).accepted,false);
});
test('malformed/truncated/duplicated XML fails', () => {
    for (const input of ['<issues>', '<issues><issue id="RawUiMotion" id="X"/></issues>',
        '<!DOCTYPE x><issues/>', '<issues><issue id="X" message="x"/></issues>', '<issues/>garbage']) assert.throws(()=>xml(input));
    assert.equal(xml('<issues/>').length,0);
});
const entry = expiry => `- rule: RawUiMotion\n  symbol: io.example.WindowAdapter.run:4\n  reason: native readback\n  issue: https://github.com/anastaci1a/localica/issues/600\n  owner: U04\n  reviewer: lead\n  expires: ${expiry}\n`;
test('escape expiry, duplicate keys and wildcard anchors fail closed', () => {
    const meta={release:'v0.33.3',steps:['U04.01']};
    assert.throws(()=>manifest(entry('U04.01'),meta,['U04.01']));
    assert.throws(()=>manifest(entry('U99.99'),meta,[]));
    assert.throws(()=>manifest(entry('v0.33.3'),meta,[]));
    assert.throws(()=>manifest(entry('v0.34.0')+'  owner: other\n',meta,[]));
    assert.throws(()=>manifest(entry('v0.34.0').replace('WindowAdapter.run:4','*:4'),meta,[]));
    assert.equal(manifest(entry('v0.34.0'),meta,[]).length,1);
});
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync,spawnSync} from 'node:child_process';
const script = new URL('../check-ui-lint.mjs',import.meta.url).pathname;
test('source-bound bootstrap, substitution, relocation and deletion through real Git blobs', () => {
    const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ui-lint-contract-'));
    const git=(...a)=>execFileSync('git',a,{cwd:dir,encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
    const put=(p,t)=>{fs.mkdirSync(path.dirname(path.join(dir,p)),{recursive:true});fs.writeFileSync(path.join(dir,p),t);};
    const commit=()=>{git('add','.');git('-c','user.name=fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture');return git('rev-parse','HEAD');};
    const code='val x = rawClock()'; const file='app/src/main/java/io/example/ui/Screen.kt';
    const baseline=(rule='RawUiMotion',fileName=file,line=3)=>`<issues><issue id="${rule}" message="policy" errorLine1="${code}" errorLine2="        ~"><location file="${fileName}" line="${line}" column="9"/></issue></issues>`;
    const invoke=(args)=>spawnSync(process.execPath,[script,...args],{cwd:dir,encoding:'utf8'});
    const common=['--baseline','app/lint-baseline.xml','--escapes','app/ui-escapes.yaml','--out','report'];
    try {
        git('init','-q');put(file,`package io.example.ui\nfun screen() {\n${code}\n}\n`);
        const base=commit();
        put('lint/src/main/java/io/github/anastaci1a/localica/lint/LocalicaIssueRegistry.kt','registry');
        put('app/lint-baseline.xml',baseline());put('app/ui-escapes.yaml','[]');
        const stage={schemaVersion:1,stage:0,integratedSha:base,release:'v1.0.0',knownSteps:['U13.01'],completedSteps:['U13.01'],bootstrapApproval:'fixture approval'};
        put('tools/ui-consolidation/stage.json',JSON.stringify(stage));let head=commit();
        let result=invoke(['bootstrap','--source',head,'--registry-sha256','a'.repeat(64),...common]);assert.equal(result.status,0,result.stderr);
        put('tools/ui-consolidation/ui-lint-bootstrap.json',fs.readFileSync(path.join(dir,'report/bootstrap.json'),'utf8'));fs.rmSync(path.join(dir,'report'),{recursive:true});head=commit();
        const check=()=>{
            put('completed.json',JSON.stringify({schemaVersion:1,sourceSha:head,release:'v1.0.0',steps:['U13.01']}));
            return invoke(['check','--base',base,'--head',head,'--stage','0','--completed-steps','completed.json',...common]);
        };
        result=check();assert.equal(result.status,0,result.stderr);
        put(file,`package io.example.ui\n\nfun screen() {\n${code}\n}\n`);put('app/lint-baseline.xml',baseline('RawUiMotion',file,4));head=commit();
        result=check();assert.equal(result.status,0,result.stderr);
        put('app/lint-baseline.xml',baseline('IndependentUiClock',file,4));head=commit();result=check();assert.equal(result.status,1,result.stderr);
        put('app/lint-baseline.xml',baseline('Unknown',file,4));head=commit();result=check();assert.equal(result.status,2);
        put('app/lint-baseline.xml',baseline('RawUiMotion',file.replace('/ui/','/ui/components/'),4));head=commit();assert.equal(check().status,2);
        fs.unlinkSync(path.join(dir,'app/lint-baseline.xml'));head=commit();assert.equal(check().status,2);
        result=invoke(['check','--base','HEAD','--head',head,'--stage','0','--completed-steps','completed.json',...common]);assert.equal(result.status,2);
    } finally { fs.rmSync(dir,{recursive:true,force:true}); }
});

test('source-only production patches trigger policy contracts', () => {
    const workflow=fs.readFileSync(new URL('../workflows/tooling-contracts.yml',import.meta.url),'utf8');
    for (const section of workflow.split('  pull_request:')) {
        assert.match(section, /app\/src\/main\/\*\*/);
    }
    assert.match(workflow, /check-ui-lint/);
});
