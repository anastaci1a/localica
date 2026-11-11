import {test} from 'node:test';
import assert from 'node:assert/strict';
import {xml,manifest,compare,rules,transferIds,transferGrowth,transferRelabels,recordedOwnerMoves,codeFingerprint,markedConstruct,sameIncident,transferReanchors} from '../check-ui-lint.mjs';
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
    const selector=fs.readFileSync(new URL('../select-tooling-contracts.mjs',import.meta.url),'utf8');
    assert.match(selector, /app\/src\/main\/\*\*/);
    assert.match(workflow, /node \.github\/select-tooling-contracts\.mjs/);
    assert.match(workflow, /if: needs\.identity\.outputs\.contracts == 'true'/);
    assert.doesNotMatch(workflow.split('permissions:')[0], /paths:/);
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
test('the U10.03 tween exception admits at most 3 entries owned by U11.04, once', () => {
    const tag = 'U10.03-tween-2026-11-07', e = (n, extra = {}) => ({rule:rules[0],path:'app/src/main/A.kt',fqn:'p.f',codeSha256:'t'+n,removedBy:'U11.04',reason:'U11.04 removes',exception:tag,...extra});
    const base = [{...e(0), exception:undefined}].map(({exception, ...x}) => x), grown = [...base, e(1), e(2), e(3)];
    transferGrowth(base, grown); transferIds(grown, ['U11.04'], []);
    assert.throws(() => transferGrowth(base, [...grown, e(4)]), /over its limit/);
    assert.throws(() => transferGrowth(grown, [...grown, e(5)]), /reused/);
    assert.throws(() => transferIds([e(1, {removedBy:'U10.05'})], ['U10.05','U11.04'], []), /Unrecorded/);
    assert.throws(() => transferIds(grown, ['U11.04'], ['U11.04']), /expired/);
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
    const [, [second, {limit:secondLimit}]] = Object.entries(transferRelabels), rest = relabeled.slice(limit);
    const moveAgain = (n, t = second) => relabeled.map((e, i) => i >= limit && i < limit + n ? {...e, removedBy:'U06.04', relabel:t} : e);
    assert.ok(rest.length <= secondLimit); transferGrowth(relabeled, moveAgain(rest.length)); transferIds(moveAgain(rest.length), known, []);
    assert.throws(() => transferGrowth(relabeled, moveAgain(rest.length, tag)), /reused/);
    assert.throws(() => transferGrowth(moveAgain(1), moveAgain(2)), /reused/);
    assert.throws(() => transferGrowth(moveAgain(1), moveAgain(1).map((e, i) => i ? e : {...e, removedBy:'U06.04', relabel:second})), /cannot change/);
    const [m] = recordedOwnerMoves, shadow = entry(0, {codeSha256:m.codeSha256, removedBy:m.from});
    transferGrowth([shadow], [{...shadow, removedBy:m.to}]);
    assert.throws(() => transferGrowth([{...shadow, removedBy:m.to}], [shadow]), /cannot change/);
    for (const r of recordedOwnerMoves) { const e = entry(0, {codeSha256:r.codeSha256, removedBy:r.from});
        transferGrowth([e], [{...e, removedBy:r.to}]);
        assert.throws(() => transferGrowth([e], [{...e, removedBy:'U10.04'}]), /cannot change/);
        assert.throws(() => transferGrowth([{...e, codeSha256:'other'}], [{...e, codeSha256:'other', removedBy:r.to}]), /cannot change/); }
});
// Lead ruling 2026-11-10 (v0.34.0): one incident whose line was edited around its unchanged marked construct.
const row = (code, marker, span, extra = {}) => {
    const r = {rule:rules[9],path:'app/src/main/S.kt',fqn:'p.move',message:'policy',originalLine:1,...extra};
    const codeSha256 = codeFingerprint(code, marker, 0);
    return {...r, codeSha256, identity:JSON.stringify([r.rule,r.path,r.fqn,codeSha256]), ...markedConstruct(code, ' '.repeat(marker) + span, marker)};
};
const shapes = [ // The four real shapes: longer line, shifted construct, changed tail, changed tail on a guarded gate.
    [row('        Column(Modifier.fillMaxSize()', 8, '~~~~~~'), row('        Column(Modifier.fillMaxSize().glassPopupProjection(home, backdrop)', 8, '~~~~~~')],
    [row('            scope.launch { lens.snapTo(position) }', 32, '~~~~~~'), row('            if (delta != 0f) scope.launch { lens.snapTo(position) }', 49, '~~~~~~')],
    [row('                    raw = if (inside) lens.value else', 39, '~~~~~'), row('                    raw = if (inside) lens.value else under', 39, '~~~~~')],
    [row('            if (handleReveal > 0f) Box(Modifier.graphicsLayer { alpha = handleReveal }) {', 16, '~'.repeat(17)),
        row('            if (handleReveal > 0f) Box(Modifier.glassShadowOpacity { handleReveal }) {', 16, '~'.repeat(17))]];
test('a same-incident re-anchor onto an edited line is listed separately, never as removed or added', () => {
    for (const [old, now] of shapes) {
        const result = compare([old], [now], 0);
        assert.equal(result.accepted, true, now.construct); assert.equal(result.added.length, 0); assert.equal(result.removed.length, 0);
        assert.deepEqual(result.sameIncidents.map(s => [s.from.identity, s.to.identity, s.construct]), [[old.identity, now.identity, old.construct]]);
    }
    assert.equal(shapes[1][1].construct, 'snapTo'); assert.equal(compare([], [], 0).sameIncidents.length, 0);
});
test('same-incident re-anchors reject every other difference, ambiguity and already matched rows', () => {
    const [old, now] = shapes[1], code = '            if (delta != 0f) scope.launch { lens.snapTo(position) }';
    const rejects = {
        construct: row('            if (delta != 0f) scope.launch { lens.animateTo(position) }', 49, '~~~~~~'),
        rule: {...now, rule:rules[0]}, message: {...now, message:'other'}, fqn: {...now, fqn:'p.other'}, path: {...now, path:'app/src/main/T.kt'},
        twice: row('            scope.launch { lens.snapTo(a); lens.snapTo(b) }', 32, '~~~~~~'),
        absent: row(code, 49, '~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~'),
        underline: row(code, 49, '~~~~~~~'), marker: {...now, ...markedConstruct(code, ' '.repeat(49) + '~~~~~~', 48)}};
    for (const [why, d] of Object.entries(rejects)) {
        const result = compare([old], [d], 0); assert.equal(result.accepted, false, why); assert.equal(result.sameIncidents.length, 0, why);
        assert.equal(result.added.length, 1, why); assert.equal(result.removed.length, 1, why);
    }
    // One gone row against two candidates (or two gone rows against one) is ambiguous: nothing pairs, never by order.
    const second = row('            if (moved) scope.launch { lens.snapTo(position) }', 43, '~~~~~~');
    assert.equal(compare([old], [now, second], 0).sameIncidents.length, 0);
    const otherOld = row('        scope.launch { lens.snapTo(position) }', 28, '~~~~~~');
    assert.equal(compare([old, otherOld], [now], 0).sameIncidents.length, 0);
    // A row that already matched by identity or by the existing re-anchor key is never a candidate.
    const kept = compare([old], [old, now], 0); assert.equal(kept.sameIncidents.length, 0); assert.equal(kept.added.length, 1);
    const drifted = compare([old], [{...old, fqn:'p.other', identity:'drifted'}, now], 0);
    assert.equal(drifted.reanchored, 1); assert.equal(drifted.sameIncidents.length, 0); assert.equal(drifted.added.length, 1);
});
test('a re-anchored transfer replaces exactly its prior entry and must be the same incident', () => {
    const [old, now] = shapes[3], line = ' '.repeat(16) + '~'.repeat(17);
    const prior = {rule:now.rule,path:now.path,fqn:now.fqn,codeSha256:old.codeSha256,removedBy:'U10.05',reason:'U10.05 removes'};
    const code = '            if (handleReveal > 0f) Box(Modifier.graphicsLayer { alpha = handleReveal }) {';
    const moved = {...prior, codeSha256:now.codeSha256, reanchoredFrom:{codeSha256:old.codeSha256, errorLine1:code, errorLine2:line, message:'policy', ordinal:0}};
    const [id] = transferIds([moved], ['U10.05'], []); transferGrowth([prior], [moved]); transferGrowth([moved], [moved]); transferGrowth([moved], []);
    assert.equal(compare([], [now], 0, [id]).accepted, true);
    assert.deepEqual(transferReanchors([moved], [now]).map(t => [t.from.codeSha256, t.to.codeSha256]), [[old.codeSha256, now.codeSha256]]);
    assert.throws(() => transferGrowth([prior], [prior, moved]), /only shrink/);
    assert.throws(() => transferGrowth([prior], [moved, {...moved, fqn:'p.other'}]), /only shrink/);
    assert.throws(() => transferGrowth([prior], [{...moved, removedBy:'U10.04'}]), /only shrink/);
    assert.throws(() => transferIds([{...moved, reanchoredFrom:{...moved.reanchoredFrom, ordinal:1}}], ['U10.05'], []), /prior fingerprint/);
    assert.throws(() => transferIds([{...moved, reanchoredFrom:{...moved.reanchoredFrom, extra:1}}], ['U10.05'], []), /Invalid transfer re-anchor/);
    assert.throws(() => transferReanchors([moved], [{...now, message:'other'}]), /not the same incident/);
    assert.throws(() => transferReanchors([moved], [{...now, construct:'handleReveal >= 0f'}]), /not the same incident/);
    assert.throws(() => transferReanchors([moved], []), /not the same incident/);
});
test('a same-incident row still needs the whole-line anchor through real Git blobs', () => {
    const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ui-lint-same-'));
    const git=(...a)=>execFileSync('git',a,{cwd:dir,encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
    const put=(p,t)=>{fs.mkdirSync(path.dirname(path.join(dir,p)),{recursive:true});fs.writeFileSync(path.join(dir,p),t);};
    const commit=()=>{git('add','.');git('-c','user.name=fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture');return git('rev-parse','HEAD');};
    const file='app/src/main/java/io/example/ui/Screen.kt', old='    scope.launch { snapTo(0) }', edited='    if (moved) scope.launch { snapTo(0) }';
    const baseline=(code,column)=>`<issues><issue id="RawUiMotion" message="policy" errorLine1="${code}" errorLine2="${' '.repeat(column-1)}~~~~~~"><location file="${file}" line="3" column="${column}"/></issue></issues>`;
    const invoke=(args)=>spawnSync(process.execPath,[script,...args],{cwd:dir,encoding:'utf8'});
    const common=['--baseline','app/lint-baseline.xml','--escapes','app/ui-escapes.yaml','--out','report'];
    try {
        git('init','-q');put(file,`package io.example.ui\nfun screen() {\n${old}\n}\n`);const base=commit();
        put('lint/src/main/java/io/github/anastaci1a/localica/lint/LocalicaIssueRegistry.kt','registry');
        put('app/lint-baseline.xml',baseline(old,20));put('app/ui-escapes.yaml','[]');put('app/build.gradle.kts','versionName = "1.0.0"');
        put('tools/ui-consolidation/stage.json',JSON.stringify({schemaVersion:1,stage:0,integratedSha:base,release:'v1.0.0',knownSteps:['U13.01'],completedSteps:['U13.01'],bootstrapApproval:'fixture approval'}));
        let head=commit(); assert.equal(invoke(['bootstrap','--source',head,'--registry-sha256','a'.repeat(64),...common]).status,0);
        put('tools/ui-consolidation/ui-lint-bootstrap.json',fs.readFileSync(path.join(dir,'report/bootstrap.json'),'utf8'));fs.rmSync(path.join(dir,'report'),{recursive:true});head=commit();
        const check=()=>{put('completed.json',JSON.stringify({schemaVersion:1,sourceSha:head,release:'v1.0.0',steps:['U13.01']}));
            return invoke(['check','--base',base,'--head',head,'--stage','0','--completed-steps','completed.json',...common]);};
        put(file,`package io.example.ui\nfun screen() {\n${edited}\n}\n`);put('app/lint-baseline.xml',baseline(edited,31));head=commit();
        let result=check(); assert.equal(result.status,0,result.stderr);
        const report=JSON.parse(fs.readFileSync(path.join(dir,'report/report.json'),'utf8'));
        assert.equal(report.sameIncidentReanchored.baseline.length,1); assert.equal(report.perRule.RawUiMotion.removed,0); assert.equal(report.perRule.RawUiMotion.added,0);
        // The head row's errorLine1 must still equal the real source line: the old text cannot ride on an edited line.
        put('app/lint-baseline.xml',baseline(old,20));head=commit(); result=check(); assert.equal(result.status,2); assert.match(result.stderr,/stale source anchor/);
        put(file,`package io.example.ui\nfun screen() {\n    if (moved) scope.launch { snapTo(0); snapTo(1) }\n}\n`);
        put('app/lint-baseline.xml',baseline('    if (moved) scope.launch { snapTo(0); snapTo(1) }',31));head=commit(); assert.equal(check().status,1);
    } finally { fs.rmSync(dir,{recursive:true,force:true}); }
});
test('a re-anchored transfer leaves its prior baseline identity out of the removed rows, one-to-one', () => {
    const [old, now] = shapes[3], ledgerId = JSON.stringify([now.rule, now.path, now.fqn, now.codeSha256]);
    const result = compare([old], [now], 0, [ledgerId], [old.identity]);
    assert.equal(result.accepted, true); assert.equal(result.removed.length, 0); assert.equal(result.counts[old.rule].removed, 0);
    const wrong = compare([old], [now], 0, [ledgerId], [JSON.stringify([now.rule, now.path, now.fqn, 'f'.repeat(64)])]);
    assert.equal(wrong.removed.length, 1); assert.equal(wrong.counts[old.rule].removed, 1);
    assert.equal(compare([old, {...old, identity:'twin'}], [now], 0, [ledgerId], [old.identity]).removed.length, 1);
});
test('a re-anchored transfer can be re-anchored again on a later edit, still one-for-one', () => {
    const [old, now] = shapes[3], line = ' '.repeat(16) + '~'.repeat(17);
    const prior = {rule:now.rule,path:now.path,fqn:now.fqn,codeSha256:old.codeSha256,removedBy:'U10.05',reason:'U10.05 removes'};
    const code1 = '            if (handleReveal > 0f) Box(Modifier.graphicsLayer { alpha = handleReveal }) {';
    const first = {...prior, codeSha256:now.codeSha256, reanchoredFrom:{codeSha256:old.codeSha256, errorLine1:code1, errorLine2:line, message:'policy', ordinal:0}};
    const code2 = '            if (handleReveal > 0f) Box(Modifier.glassShadowOpacity { handleReveal }) {';
    const next = row('            if (handleReveal > 0f) HandleSlot(Modifier.glassShadowOpacity { handleReveal }) {', 16, '~'.repeat(17));
    const second = {...prior, codeSha256:next.codeSha256, reanchoredFrom:{codeSha256:now.codeSha256, errorLine1:code2, errorLine2:line, message:'policy', ordinal:0}};
    transferGrowth([first], [second]); transferIds([second], ['U10.05'], []);
    assert.deepEqual(transferReanchors([second], [next]).map(t => t.to.codeSha256), [next.codeSha256]);
    assert.throws(() => transferGrowth([first], [{...second, removedBy:'U10.04'}]), /only shrink/);
    assert.throws(() => transferGrowth([first], [first, second]), /only shrink/);
});
