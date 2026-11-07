import {test} from 'node:test';
import assert from 'node:assert/strict';
import {xml,manifest,compare,rules,transferIds,transferGrowth,transferRelabels,recordedOwnerMoves,codeFingerprint} from '../check-ui-lint.mjs';
const incident = {rule:rules[0],path:'app/src/main/A.kt',codeSha256:'c',identity:'old'};
test('new incident and equal-count substitution fail', () => {
    assert.equal(compare([incident],[incident,{...incident,codeSha256:'d',identity:'new'}],0).accepted,false);
    assert.equal(compare([incident],[{...incident,codeSha256:'d',identity:'new'}],0).accepted,false);
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
        put('app/lint-baseline.xml',baseline());put('app/ui-escapes.yaml','[]');put('app/build.gradle.kts','versionName = "1.0.0"');
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
test('itemized transfers admit only listed identities, reject stale and expired entries', () => {
    const entry = {rule:rules[6],path:'app/src/main/A.kt',fqn:'p.f',codeSha256:'x',removedBy:'U10.03',reason:'U10.03 removes'};
    const [id] = transferIds([entry],['U10.03'],[]);
    const moved = {rule:rules[6],identity:id};
    assert.equal(compare([incident],[incident,moved],0,[id]).accepted,true);
    assert.equal(compare([incident],[incident,moved,{...incident,identity:'new'}],0,[id]).accepted,false);
    assert.throws(() => compare([incident],[incident],0,[id]), /Stale transfer/);
    assert.throws(() => transferIds([entry],['U10.03'],['U10.03']), /expired/);
    assert.throws(() => transferIds([entry,entry],['U10.03'],[]), /Duplicate/);
});
test('an unchanged line whose enclosing-symbol anchor drifted is re-anchored, not added', () => {
    const drifted = {...incident,fqn:'p.other',identity:'drifted'};
    assert.equal(compare([incident],[drifted],0).accepted,true);
    assert.equal(compare([incident],[drifted,{...drifted,identity:'second'}],0).accepted,false);
    assert.equal(compare([incident],[{...drifted,rule:rules[1]}],0).accepted,false);
});
test('re-anchoring never absorbs an edited line, even when a function moved above it', () => {
    // Fixture: one file before and after a helper moved above two flagged lines; the anchor drifts p.Player -> p.helper.
    const at = (fqn, code, marker, ordinal = 0) => {
        const codeSha256 = codeFingerprint(code, marker, ordinal);
        return {rule:rules[0],path:'app/src/main/P.kt',fqn,codeSha256,identity:JSON.stringify([rules[0],'app/src/main/P.kt',fqn,codeSha256])};
    };
    const kept = '    val a = tween<Float>(300)', other = '    val b = tween<Float>(300)';
    const before = [at('p.Player', kept, 12), at('p.Player', other, 12)];
    const drifted = compare(before, [at('p.helper', kept, 12), at('p.helper', other, 12)], 0);
    assert.equal(drifted.accepted, true); assert.equal(drifted.reanchored, 2);
    // Whitespace-only reformatting keeps the fingerprint (marker is relative to the trimmed text).
    assert.equal(compare(before, [at('p.helper', kept, 12), at('p.helper', '\t' + other.trim(), 9)], 0).accepted, true);
    // Edited text, a moved marker, or a new ordinal among identical lines is a new incident: flagged, never re-anchored.
    for (const [code, marker, ordinal] of [['    val b = tween<Float>(200)',12,0], ['    val c = tween<Float>(300)',12,0], [other,14,0], [other,12,1]]) {
        const result = compare(before, [at('p.helper', kept, 12), at('p.helper', code, marker, ordinal)], 0);
        assert.equal(result.accepted, false, code); assert.equal(result.added.length, 1); assert.equal(result.reanchored, 1);
    }
});
test('the ledger grows only through the recorded one-time exception, within its limit', () => {
    const entry = (n, extra = {}) => ({rule:rules[6],path:'app/src/main/A.kt',fqn:'p.f',codeSha256:'x'+n,removedBy:'U10.03',reason:'U10.03 removes',...extra});
    const tag = {exception:'U10.02-ledger-2026-11-07'};
    const base = [entry(0), entry(1)];
    transferGrowth(base, [entry(0)]);
    assert.throws(() => transferGrowth(base, [...base, entry(2)]), /only shrink/);
    const grown = [...base, ...Array.from({length:18}, (_, i) => entry(10 + i, tag))];
    transferGrowth(base, grown); transferIds(grown, ['U10.03'], []);
    assert.throws(() => transferGrowth(base, [...grown, entry(40, tag)]), /over its limit/);
    assert.throws(() => transferGrowth(grown, [...grown, entry(41, tag)]), /reused/);
    transferGrowth(grown, grown.slice(1));
    assert.throws(() => transferIds([entry(3, {exception:'other'})], ['U10.03'], []), /Unrecorded/);
    assert.throws(() => transferIds([entry(3, {...tag, removedBy:'U11.01'})], ['U10.03','U11.01'], []), /Unrecorded/);
});
test('transfer owners change only through the recorded one-off relabel, once and within its limit', () => {
    const entry = (n, extra = {}) => ({rule:rules[6],path:'app/src/main/A.kt',fqn:'p.f',codeSha256:'r'+n,removedBy:'U10.03',reason:'U10.03 removes',...extra});
    const [tag, {limit}] = Object.entries(transferRelabels)[0]; const known = ['U10.03','U10.04','U06.04'];
    const base = Array.from({length:limit + 2}, (_, i) => entry(i));
    const move = (list, n, to = 'U10.04') => list.map((e, i) => i < n ? {...e, removedBy:to, relabel:tag} : e);
    assert.throws(() => transferGrowth(base, base.map((e, i) => i ? e : {...e, removedBy:'U10.04'})), /cannot change/);
    const relabeled = move(base, limit); transferGrowth(base, relabeled); transferIds(relabeled, known, []);
    assert.throws(() => transferGrowth(base, move(base, limit + 1)), /over its limit/);
    transferGrowth(relabeled, relabeled.slice(1));
    assert.throws(() => transferGrowth(relabeled, move(relabeled, limit + 1)), /reused/);
    assert.throws(() => transferGrowth(relabeled, relabeled.map((e, i) => i ? e : {...e, removedBy:'U06.04'})), /cannot change/);
    assert.throws(() => transferGrowth(base, base.map((e, i) => i ? e : {...e, relabel:tag})), /cannot change/);
    assert.throws(() => transferGrowth(base, [...base, entry(99, {removedBy:'U10.04', relabel:tag})]), /only shrink/);
    assert.throws(() => transferIds([entry(1, {relabel:'other', removedBy:'U10.04'})], known, []), /Unrecorded transfer relabel/);
    assert.throws(() => transferIds([entry(1, {relabel:tag})], known, []), /Unrecorded transfer relabel/);
    assert.throws(() => transferIds([entry(1, {relabel:tag, removedBy:'U10.04', exception:'U10.02-ledger-2026-11-07'})], known, []), /Unrecorded/);
    const [m] = recordedOwnerMoves, shadow = entry(0, {codeSha256:m.codeSha256, removedBy:m.from});
    transferGrowth([shadow], [{...shadow, removedBy:m.to}]);
    assert.throws(() => transferGrowth([{...shadow, removedBy:m.to}], [shadow]), /cannot change/);
    for (const r of recordedOwnerMoves) { const e = entry(0, {codeSha256:r.codeSha256, removedBy:r.from});
        transferGrowth([e], [{...e, removedBy:r.to}]);
        assert.throws(() => transferGrowth([e], [{...e, removedBy:'U10.04'}]), /cannot change/);
        assert.throws(() => transferGrowth([{...e, codeSha256:'other'}], [{...e, codeSha256:'other', removedBy:r.to}]), /cannot change/); }
});
