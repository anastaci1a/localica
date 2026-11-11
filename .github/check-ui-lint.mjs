import {strictJson} from '../tools/ui-consolidation/strict-json.mjs';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
export const rules = ['RawUiMotion','IndependentUiClock','RawPresentationTransform','RawShadowOrZOrder',
    'ReplacePersistentShape','UnownedSurfaceLifetime','RawMotionMap','MotionReadInComposition',
    'LiteralUiSpacing','NonPresetScreenLayout','UnvalidatedUiSpec','UiLayerDependency','UnreviewedUiEscape'];
const digest = data => crypto.createHash('sha256').update(data).digest('hex');
const requireThat = (ok, why) => { if (!ok) throw Error(why); };
const safePath = p => typeof p === 'string' && !path.isAbsolute(p) && !p.split('/').some(s => s === '..' || s === '.') && /^[\w./-]+$/.test(p);
function decode(s) {
    requireThat(!/&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[a-fA-F0-9]+;)/.test(s), 'Invalid XML entity');
    return s.replace(/&(#x[a-fA-F0-9]+|#\d+|amp|lt|gt|quot|apos);/g, (_, x) =>
        x.startsWith('#') ? String.fromCodePoint(x[1] === 'x' ? parseInt(x.slice(2), 16) : +x.slice(1)) :
            ({amp:'&',lt:'<',gt:'>',quot:'"',apos:"'"})[x]);
}
export function xml(text) {
    requireThat(!/<!DOCTYPE|<!ENTITY/.test(text), 'XML declarations forbidden');
    text = text.replace(/^\s*<\?xml[^?]*\?>/, '').replace(/<!--[\s\S]*?-->/g, '');
    const stack = [], issues = []; let current, root = false, end = 0;
    for (const m of text.matchAll(/<((?:"[^"]*"|[^"<>])*)>/g)) {
        requireThat(text.slice(end, m.index).trim() === '', 'XML mixed content'); end = m.index + m[0].length;
        let t = m[1].trim();
        if (t.startsWith('/')) { requireThat(stack.pop() === t.slice(1), 'Unbalanced XML'); if (t === '/issue') current = null; continue; }
        const self = t.endsWith('/'); if (self) t = t.slice(0, -1).trim();
        const name = t.match(/^\w+/)?.[0]; requireThat(['issues','issue','location'].includes(name), 'Unknown XML element');
        const attrs = {}; let tail = t.slice(name.length);
        while (tail.trim()) {
            const a = tail.match(/^\s+([\w-]+)\s*=\s*"([^"]*)"/);
            requireThat(a && !(a[1] in attrs), 'Malformed or duplicate XML attribute'); attrs[a[1]] = decode(a[2]); tail = tail.slice(a[0].length);
        }
        if (name === 'issues') { requireThat(!root && stack.length === 0, 'Duplicate root'); root = true; }
        if (name === 'issue') { requireThat(stack.join() === 'issues' && attrs.id && attrs.message, 'Invalid issue'); current = {...attrs, locations: []}; issues.push(current); }
        if (name === 'location') { requireThat(current && stack.join() === 'issues,issue' && attrs.file && +attrs.line > 0, 'Invalid location'); current.locations.push(attrs); }
        if (!self) stack.push(name);
    }
    requireThat(root && !stack.length && text.slice(end).trim() === '', 'Truncated XML');
    requireThat(issues.every(i => i.locations.length), 'Missing diagnostic location'); return issues;
}
export function manifest(text, metadata, known) {
    // The manifest format is a strict YAML list with scalar values; no aliases/tags/multiline values.
    if (text.trim() === '[]') return [];
    const entries = []; let row;
    for (const line of text.split('\n').filter(l => l.trim() && !l.trim().startsWith('#'))) {
        const m = line.match(/^(?:- |  )([A-Za-z]+):\s*(.+?)\s*$/); requireThat(m, 'Invalid escape YAML');
        if (line.startsWith('- ')) { row = {}; entries.push(row); }
        requireThat(row && !(m[1] in row), 'Duplicate escape key');
        let value = m[2];
        if (value.startsWith('"')) value = JSON.parse(value);
        else if (value.startsWith("'")) { requireThat(value.endsWith("'"), 'Invalid YAML quote'); value = value.slice(1,-1).replace(/''/g,"'"); }
        requireThat(typeof value === 'string' && !/[&*!{}\[\]]/.test(value), 'Unsafe YAML scalar'); row[m[1]] = value;
    }
    const ids = new Set();
    for (const e of entries) {
        requireThat(Object.keys(e).sort().join() === 'expires,issue,owner,reason,reviewer,rule,symbol', 'Escape keys');
        requireThat(rules.includes(e.rule) && /^[\w.$]+:[1-9]\d*$/.test(e.symbol), 'Escape identity');
        requireThat(e.reviewer === 'lead' && /^https:\/\/github.com\/anastaci1a\/localica\/issues\/[1-9]\d*$/.test(e.issue), 'Escape review');
        requireThat(e.reason && e.owner, 'Empty escape'); const id = e.rule + ':' + e.symbol;
        requireThat(!ids.has(id), 'Duplicate escape'); ids.add(id);
        if (/^U/.test(e.expires)) requireThat(known.includes(e.expires) && !metadata.steps.includes(e.expires), 'Expired or unknown step');
        else {
            const version = v => { requireThat(/^v\d+\.\d+\.\d+$/.test(v), 'Invalid release'); return v.slice(1).split('.').map(Number); };
            const a = version(e.expires), b = version(metadata.release);
            const i = a.findIndex((v,j) => v !== b[j]); requireThat(i >= 0 && a[i] > b[i], 'Expired release');
        }
    }
    return entries;
}
/** Recorded one-time ledger growth (U14 note). Lead decision 2026-11-07 3:15 AM: U10.02 may add at most 18 rewritten
 * phase-consumer transfers tagged U10.03, in one commit; the tag can never admit growth again. */
export const transferExceptions = {'U10.02-ledger-2026-11-07': {removedBy: 'U10.03', limit: 18},
    /** Lead decision 2026-11-07 12:10 PM (1): U10.03 retires the lyrics, artwork-layout and crop tweens onto Engine owners whose
     * values still feed composition geometry, once, at most 3 MotionReadInComposition entries, expiring at U11.04 (layout/draw
     * sampling). Recorded route: the lyrics value also reaches LocalicaScreen through state, polarity.presentLightMix(lightMix)
     * -> playerLightMix/playerLightInk; that route is part of entry 1 and expires with it. */
    'U10.03-tween-2026-11-07': {removedBy: 'U11.04', limit: 3}};
/** Recorded one-off relabel (U14 note, tools/ui-consolidation/u1003-relabel.txt). Lead decision 2026-11-07 10:10 AM: U10.03
 * entries whose real owner is another step (artwork swipe, snapshot recording, cover order, title input band) move to that
 * step once, in one commit, at most 22 entries; every other removedBy change is rejected. */
export const transferRelabels = {'U10.03-relabel-2026-11-07': {from: 'U10.03', limit: 22},
    /** Lead decision 2026-11-07 12:10 PM (2): U10.03 completes on the tween and curve work; its remaining 22 transfers (the player
     * phase read and phase-derived lifetime gates) move once to the step that samples them (U11.04/U11.01 geometry, U10.05 Components). */
    'U10.03-relabel-2-2026-11-07': {from: 'U10.03', limit: 22}};
/** Untagged owner moves already in the ledger history (lead decision 2026-11-07 ~8 AM, stage2 f449bf56 = stage3 b1fd8cbb), and
 * the recorded addition to the U10.03 relabel map (lead decision 2026-11-07 11:00 AM): the PlayerSurface scroll gate shares
 * its line with the baselined player Column, so it moves to U11.04, the step that replaces that Column. */
export const recordedOwnerMoves = [{codeSha256: '20f5c3f87152c38dc50f6bd4be3be3085fe522574fa028dfc116923d1b338f15', from: 'U04.01a', to: 'U04.01b'},
    {codeSha256: 'f7cb4d564f22c78a17a817c10cc0bfd565fe2d741765b679d9f9537d54882f8f', from: 'U10.03', to: 'U11.04'}];
const transferId = e => JSON.stringify([e.rule,e.path,e.fqn,e.codeSha256]);
/** The marked construct of a row: the errorLine2 underline span within errorLine1, and how often it occurs on that line. */
export function markedConstruct(code, underline, marker) {
    const m = underline?.match(/^( *)([~^]+)\s*$/);
    if (!m || m[1].length !== marker || marker + m[2].length > code.length) return {span:'', construct:'', occurrences:0};
    const construct = code.slice(marker, marker + m[2].length); let occurrences = 0;
    for (let i = code.indexOf(construct); i >= 0; i = code.indexOf(construct, i + 1)) occurrences++;
    return {span:m[2], construct, occurrences};
}
/** Lead ruling 2026-11-10 (v0.34.0 baseline repair): one incident whose line was edited around it. Rule, path, enclosing symbol,
 * message, underline and marked construct text are byte-identical, and the construct occurs exactly once on the new line. */
export const sameIncident = (old, now) => !!old.construct && old.rule === now.rule && old.path === now.path && old.fqn === now.fqn &&
    old.message === now.message && old.span === now.span && old.construct === now.construct && now.occurrences === 1;
/** A transfer re-anchored onto its edited line records its prior row, which must reproduce the prior fingerprint. */
function priorTransferRow(e) {
    const r = e.reanchoredFrom;
    requireThat(r && typeof r === 'object' && Object.keys(r).sort().join() === 'codeSha256,errorLine1,errorLine2,message,ordinal' &&
        [r.errorLine1, r.errorLine2, r.message].every(v => typeof v === 'string' && v) && Number.isInteger(r.ordinal) && r.ordinal >= 0 &&
        r.codeSha256 !== e.codeSha256, 'Invalid transfer re-anchor');
    const marker = r.errorLine2.search(/[~^]/), mark = markedConstruct(r.errorLine1, r.errorLine2, marker);
    requireThat(mark.construct && codeFingerprint(r.errorLine1, marker, r.ordinal) === r.codeSha256, 'Transfer re-anchor does not reproduce its prior fingerprint');
    return {rule:e.rule, path:e.path, fqn:e.fqn, message:r.message, ...mark};
}
/** Every re-anchored transfer names a head row that is the same incident as its recorded prior row. */
export function transferReanchors(entries, after) {
    return entries.filter(e => 'reanchoredFrom' in e).map(e => {
        const row = after.find(d => d.identity === transferId(e));
        requireThat(row && sameIncident(priorTransferRow(e), row), 'Transfer re-anchor is not the same incident');
        return {rule:e.rule, path:e.path, fqn:e.fqn, construct:row.construct, from:{codeSha256:e.reanchoredFrom.codeSha256}, to:{codeSha256:e.codeSha256, line:row.originalLine}};
    });
}
/** Lead-approved ownership transfers (2026-11-07): itemized incidents a named future step removes; shrink-only along history. */
export function transferIds(entries, known, completed) {
    requireThat(Array.isArray(entries), 'Transfer entries');
    const ids = entries.map(e => {
        const keys = Object.keys(e).filter(k => k !== 'exception' && k !== 'relabel' && k !== 'reanchoredFrom').sort().join();
        requireThat(keys === 'codeSha256,fqn,path,reason,removedBy,rule' && rules.includes(e.rule) && e.reason &&
            known.includes(e.removedBy) && !completed.includes(e.removedBy), 'Invalid or expired transfer');
        requireThat(!('exception' in e) || transferExceptions[e.exception]?.removedBy === e.removedBy, 'Unrecorded transfer exception');
        requireThat(!('relabel' in e) || !('exception' in e) && transferRelabels[e.relabel] && transferRelabels[e.relabel].from !== e.removedBy, 'Unrecorded transfer relabel');
        if ('reanchoredFrom' in e) priorTransferRow(e);
        return transferId(e);
    });
    requireThat(new Set(ids).size === ids.length, 'Duplicate transfer'); return ids;
}
/** Newer entries must be a subset of older ones with unchanged owners, except a recorded exception's entries or relabel, each admitted once up to its limit. */
export function transferGrowth(older, newer) {
    const prior = new Set(older.map(transferId)), kept = new Set(newer.map(transferId)); let grown = newer.filter(e => !prior.has(transferId(e)));
    // A same-incident re-anchor replaces exactly one prior entry that is otherwise identical and no longer present.
    // The prior entry may itself carry an earlier reanchoredFrom; only the remaining fields must be identical.
    const same = (o, e) => { const strip = ({reanchoredFrom, ...rest}) => rest, flat = x => JSON.stringify(Object.entries(x).sort());
        return flat(strip(o)) === flat({...strip(e), codeSha256:e.reanchoredFrom.codeSha256}); };
    const source = e => e.reanchoredFrom ? older.filter(o => !kept.has(transferId(o)) && same(o, e)) : [];
    const replacing = grown.filter(e => source(e).length === 1); grown = grown.filter(e => !replacing.includes(e));
    requireThat(new Set(replacing.map(e => transferId(source(e)[0]))).size === replacing.length, 'Transfer re-anchor reused');
    requireThat(grown.every(e => e.exception && !e.relabel), 'Transfer entries can only shrink');
    const was = new Map(older.map(e => [transferId(e), e])), before = e => was.get(transferId(e));
    const moved = newer.filter(e => before(e) && (before(e).removedBy !== e.removedBy || before(e).relabel !== e.relabel));
    const recorded = e => !e.relabel && !before(e).relabel && recordedOwnerMoves.some(m => m.codeSha256 === e.codeSha256 && m.from === before(e).removedBy && m.to === e.removedBy);
    requireThat(moved.every(e => recorded(e) || e.relabel && !before(e).relabel && e.removedBy !== before(e).removedBy && transferRelabels[e.relabel]?.from === before(e).removedBy), 'Transfer owners cannot change');
    for (const [tag, x] of Object.entries(transferRelabels)) { const changed = moved.filter(e => e.relabel === tag && !recorded(e));
        requireThat(!changed.length || changed.length <= x.limit && older.every(e => e.relabel !== tag), 'Transfer relabel reused or over its limit'); }
    for (const [tag, x] of Object.entries(transferExceptions)) { const added = grown.filter(e => e.exception === tag);
        requireThat(!added.length || added.length <= x.limit && older.every(e => e.exception !== tag), 'Transfer exception reused or over its limit'); }
}
// Whitespace-normalized text, marker column relative to the trimmed text, and ordinal among identical source lines.
export const codeFingerprint = (code, marker, ordinal) =>
    digest(JSON.stringify([code.trim().replace(/\s+/g,' '), marker - (code.length-code.trimStart().length), ordinal]));
/** The before identity each re-anchored transfer continues: its ledger prior when that is a before identity, else the one gone
 * before row that is the same incident as the transferred head row (a transfer re-anchored more than once). */
export function transferPriorIdentities(reanchors, before, after) {
    const now = new Set(after.map(d => d.identity)), used = new Set();
    return reanchors.flatMap(t => {
        const prior = JSON.stringify([t.rule,t.path,t.fqn,t.from.codeSha256]), head = after.find(d => d.identity === JSON.stringify([t.rule,t.path,t.fqn,t.to.codeSha256]));
        const found = before.some(d => d.identity === prior) ? [prior] :
            before.filter(d => !now.has(d.identity) && !used.has(d.identity) && head && sameIncident(d, head)).map(d => d.identity);
        if (found.length !== 1) return []; used.add(found[0]); return found;
    });
}
export function compare(before, after, stage, transfers = [], transferReanchored = []) {
    const counts = Object.fromEntries(rules.map(r => [r, {before:0, after:0, removed:0, added:0}]));
    for (const d of before) counts[d.rule].before++;
    for (const d of after) counts[d.rule].after++;
    const old = new Set(before.map(d => d.identity)), now = new Set(after.map(d => d.identity));
    const moved = new Set(transfers); requireThat(transfers.every(t => now.has(t)), 'Stale transfer entry');
    let added = after.filter(d => !old.has(d.identity) && !moved.has(d.identity));
    // The enclosing-symbol heuristic drifts when a local function is added, deleted or moved above an unchanged line:
    // the same rule, file and code fingerprint (text, marker and ordinal) is the same incident, re-anchored.
    const key = d => JSON.stringify([d.rule,d.path,d.codeSha256]), gone = new Map(), reanchored = [];
    for (const d of before.filter(d => !now.has(d.identity))) gone.set(key(d), [...(gone.get(key(d)) ?? []), d]);
    added = added.filter(d => { const same = gone.get(key(d)); if (!same?.length) return true; same.pop(); reanchored.push(d); return false; });
    // Lead ruling 2026-11-10: an incident whose line was edited around its unchanged marked construct is re-anchored, listed
    // separately and counted neither as removed nor as added; only an unambiguous one-to-one match qualifies.
    let removed = [...gone.values()].flat(); const sameIncidents = [], pairedOld = new Set(), pairedNew = new Set();
    for (const d of added) {
        const olds = removed.filter(g => sameIncident(g, d));
        if (olds.length !== 1 || added.filter(a => sameIncident(olds[0], a)).length !== 1) continue;
        pairedOld.add(olds[0]); pairedNew.add(d);
        sameIncidents.push({rule:d.rule, path:d.path, fqn:d.fqn, construct:d.construct,
            from:{identity:olds[0].identity, line:olds[0].originalLine}, to:{identity:d.identity, line:d.originalLine}});
    }
    added = added.filter(d => !pairedNew.has(d)); removed = removed.filter(d => !pairedOld.has(d));
    // A re-anchored transfer's prior identity is the same incident as its transferred head row, not a removal (one-to-one).
    for (const id of transferReanchored) { const i = removed.findIndex(d => d.identity === id); if (i >= 0) removed.splice(i, 1); }
    for (const d of added) counts[d.rule].added++;
    for (const d of removed) counts[d.rule].removed++;
    return {counts, added, removed, reanchored: reanchored.length, sameIncidents, transferred: transfers.length, accepted: added.length === 0 && (stage < 6 || after.length === 0)};
}
export function run(argv) {
    const command = argv.shift(); requireThat(['check','bootstrap'].includes(command), 'Unknown command');
    requireThat(argv.length % 2 === 0, 'Missing argument'); const o = {};
    for (let i=0;i<argv.length;i+=2) { requireThat(!o[argv[i]], 'Duplicate flag'); o[argv[i]] = argv[i+1]; }
    for (const k of ['--baseline','--escapes','--out', ...(command === 'check' ? ['--base','--head','--stage','--completed-steps'] : ['--source','--registry-sha256'])]) requireThat(o[k], `Missing ${k}`);
    for (const k of ['--baseline','--escapes']) requireThat(safePath(o[k]), 'Unsafe repository path');
    const git = (...a) => execFileSync('git', a, {maxBuffer: 64*1024*1024, stdio:['ignore','pipe','pipe']}).toString();
    const fullSha = s => { requireThat(/^[a-f0-9]{40}$/.test(s), 'Immutable SHA required'); requireThat(git('rev-parse',s).trim() === s, 'SHA mismatch'); return s; };
    const head = fullSha(o['--head'] || o['--source']), base = command === 'check' ? fullSha(o['--base']) : null;
    const blobCache = new Map();
    const blob = (ref,p) => { const key = ref+':'+p; if (!blobCache.has(key)) blobCache.set(key, git('show',key)); return blobCache.get(key); };
    const exists = (ref,p) => { try { blob(ref,p); return true; } catch { return false; } };
    const registryPath = 'lint/src/main/java/io/github/anastaci1a/localica/lint/LocalicaIssueRegistry.kt';
    const registry = digest(blob(head, registryPath));
    const known = strictJson(blob(head,'tools/ui-consolidation/stage.json'));
    requireThat(Object.keys(known).sort().join()==='bootstrapApproval,completedSteps,integratedSha,knownSteps,release,schemaVersion,stage' && known.schemaVersion === 1 && Array.isArray(known.knownSteps), 'Stage metadata');
    fullSha(known.integratedSha); git('merge-base','--is-ancestor',known.integratedSha,head);
    requireThat(known.release === 'v'+blob(head,'app/build.gradle.kts').match(/versionName\s*=\s*"([^"]+)"/)?.[1], 'Candidate release does not match app version');
    const meta = command === 'check' ? strictJson(fs.readFileSync(o['--completed-steps'],'utf8')) :
        {schemaVersion:1,sourceSha:head,steps:known.completedSteps,release:known.release};
    requireThat(Object.keys(meta).sort().join() === 'release,schemaVersion,sourceSha,steps' && meta.schemaVersion === 1 &&
        meta.sourceSha === head && Array.isArray(meta.steps) && new Set(meta.steps).size === meta.steps.length &&
        meta.steps.every(s => known.knownSteps.includes(s)) && meta.release === known.release &&
        JSON.stringify([...meta.steps].sort()) === JSON.stringify([...known.completedSteps].sort()), 'Completed-step source mismatch');
    const stage = command === 'check' ? +o['--stage'] : 0;
    requireThat(Number.isInteger(stage) && stage >= 0 && stage <= 6 && stage === known.stage, 'Frozen stage mismatch');
    const manifestText = blob(head, o['--escapes']);
    const escapes = manifest(manifestText, meta, known.knownSteps);
    const sourceFiles = git('ls-tree','-r','--name-only',head,'app/src/main').trim().split('\n').filter(f => f.endsWith('.kt'));
    for (const e of escapes) {
        const [fqn,line] = e.symbol.split(':');
        const found = sourceFiles.filter(f => {
            const source = blob(head,f), pkg = source.match(/^package\s+([\w.]+)/m)?.[1];
            return fqn.startsWith(pkg + '.') && source.split('\n')[+line - 1]?.includes(fqn.split('.').at(-1));
        });
        requireThat(found.length === 1 && /(?:Readback|Window|FrameStats|diagnostics)/i.test(found[0]), 'Missing or non-adapter escape source');
    }
    const diagnostics = ref => xml(blob(ref,o['--baseline'])).map(issue => {
        requireThat(rules.includes(issue.id), `Unknown rule ${issue.id}`);
        const loc = issue.locations[0]; let file = loc.file.replaceAll('\\','/');
        if (file.startsWith('src/')) file = 'app/' + file;
        requireThat(safePath(file) && file.startsWith('app/src/main/'), 'Invalid incident path');
        requireThat(!/\/ui\/(engine|spec|components|layout)\//.test(file), 'Migrated-package baseline');
        const code = issue.errorLine1; requireThat(code?.trim(), 'Missing code fingerprint');
        const source = blob(ref,file), lines = source.split('\n');
        const candidates = lines.flatMap((l,i) => l.trim() === code.trim() ? [i] : []);
        const at = candidates.includes(+loc.line-1) ? +loc.line-1 : candidates.length === 1 ? candidates[0] : null;
        requireThat(at !== null, `Ambiguous or stale source anchor ${file}:${loc.line}`);
        const pkg = source.match(/^package\s+([\w.]+)/m)?.[1] || '';
        const symbol = lines.slice(0,at+1).join('\n').match(/\b(?:fun|class|object)\s+(?:<[^>]*>\s*)?([\w.]+)/g)?.at(-1)?.replace(/^.*\s/,'') || path.basename(file,'.kt');
        const marker = (+loc.column > 0 ? +loc.column - 1 : issue.errorLine2?.search(/[~^]/));
        const ordinal = candidates.indexOf(at);
        const codeSha256 = codeFingerprint(code, marker, ordinal);
        const identity = JSON.stringify([issue.id,file,pkg+'.'+symbol,codeSha256]);
        return {rule:issue.id,path:file,fqn:pkg+'.'+symbol,codeSha256,originalLine:+loc.line,identity,message:issue.message,...markedConstruct(code,issue.errorLine2,marker)};
    });
    const after = diagnostics(head); requireThat(new Set(after.map(d=>d.identity)).size === after.length, 'Duplicate diagnostic identities: ' + JSON.stringify(after.filter((d,i)=>after.findIndex(a=>a.identity===d.identity)!==i).slice(0,5)));
    let before = [], bootstrap = null;
    if (command === 'bootstrap') {
        requireThat(/^[a-f0-9]{64}$/.test(o['--registry-sha256']), 'Registry artifact digest');
        bootstrap = {sourceSha:head,registryArtifactSha256:o['--registry-sha256'],baselineSha256:digest(blob(head,o['--baseline'])),
            integratedSha:known.integratedSha,approvedBy:'lead',approval:known.bootstrapApproval};
        requireThat(/^[a-f0-9]{40}$/.test(known.integratedSha) && !exists(known.integratedSha,o['--baseline']) && known.bootstrapApproval, 'Bootstrap not approved');
    } else if (exists(base,o['--baseline'])) {
        before = diagnostics(base);
    } else {
        requireThat(!exists(base,'tools/ui-consolidation/ui-lint-bootstrap.json'), 'Deleted baseline cannot bootstrap');
        requireThat(!git('log','--format=%H',base,'--',o['--baseline']).trim(), 'Previously existing baseline deleted from base');
        const receipt = strictJson(blob(head,'tools/ui-consolidation/ui-lint-bootstrap.json'));
        fullSha(receipt.sourceSha); git('merge-base','--is-ancestor',receipt.sourceSha,head);
        requireThat(receipt.integratedSha === known.integratedSha && receipt.approvedBy === 'lead' && receipt.approval === known.bootstrapApproval &&
            !exists(receipt.integratedSha,o['--baseline']) && receipt.baselineSha256 === digest(blob(receipt.sourceSha,o['--baseline'])), 'Invalid bootstrap receipt');
        const common=git('merge-base',base,receipt.integratedSha).trim();
        requireThat(common===base || common===receipt.integratedSha, 'Bootstrap base is unrelated to the integrated pin');
        before = diagnostics(receipt.sourceSha);
    }
    const transfersPath = 'tools/ui-consolidation/transfers.json'; let transfers = [], transferSameIncidents = [];
    if (exists(head,transfersPath)) {
        const load = ref => { if (!exists(ref,transfersPath)) return null; const t = strictJson(blob(ref,transfersPath));
            requireThat(Object.keys(t).sort().join() === 'approval,entries,schemaVersion' && t.schemaVersion === 1 && t.approval, 'Transfer manifest');
            transferIds(t.entries, known.knownSteps, ref === head ? meta.steps : []); return t.entries; };
        const current = load(head); transfers = transferIds(current, known.knownSteps, meta.steps);
        transferSameIncidents = transferReanchors(current, after);
        // Each ledger version is checked against its real parents, never against an unrelated branch's version.
        for (const [commit, ...parents] of git('log','--format=%H %P',head,'--',transfersPath).trim().split('\n').filter(Boolean).map(l => l.split(' ')))
            for (const parent of parents) { const prior = load(parent), now = load(commit); if (prior && now) transferGrowth(prior, now); }
        if (base) { const prior = load(base); if (prior) transferGrowth(prior, current); }
    }
    const result = compare(before,after,stage,transfers,transferPriorIdentities(transferSameIncidents,before,after));
    const report = {schemaVersion:1,command,baseSha:base,headSha:head,registrySha256:registry,
        baselineSha256:digest(blob(head,o['--baseline'])),manifestSha256:digest(manifestText),stage,completedSteps:meta.steps,
        perRule:result.counts,unmatchedIdentities:result.added,transferredIdentities:result.transferred,reanchoredIdentities:result.reanchored,
        sameIncidentReanchored:{baseline:result.sameIncidents,transfers:transferSameIncidents},escapeCounts:{before:base && exists(base,o['--escapes']) ? manifest(blob(base,o['--escapes']),meta,known.knownSteps).length : 0,after:escapes.length},
        validationErrors:[],accepted:(command === 'bootstrap' || result.accepted) && escapes.length <= 5};
    fs.mkdirSync(o['--out'],{recursive:true}); fs.writeFileSync(path.join(o['--out'],'report.json'),JSON.stringify(report,null,2)+'\n');
    if (bootstrap) fs.writeFileSync(path.join(o['--out'],'bootstrap.json'),JSON.stringify(bootstrap,null,2)+'\n');
    return report.accepted ? 0 : 1;
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    try { process.exitCode = run(process.argv.slice(2)); } catch (e) {
        console.error(e.message); process.exitCode = 2;
        const argv=process.argv.slice(2), out=argv[argv.indexOf('--out')+1];
        if (argv.includes('--out') && out && !out.startsWith('--')) {
            const report={schemaVersion:1,command:argv[0],baseSha:null,headSha:null,registrySha256:null,
                baselineSha256:null,manifestSha256:null,stage:null,completedSteps:[],perRule:{},
                unmatchedIdentities:[],escapeCounts:null,validationErrors:[e.message],accepted:false};
            fs.mkdirSync(out,{recursive:true}); fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2)+'\n');
        }
    }
}
