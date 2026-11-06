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
export function compare(before, after, stage) {
    const counts = Object.fromEntries(rules.map(r => [r, {before:0, after:0, removed:0, added:0}]));
    for (const d of before) counts[d.rule].before++;
    for (const d of after) counts[d.rule].after++;
    const old = new Set(before.map(d => d.identity)), now = new Set(after.map(d => d.identity));
    const added = after.filter(d => !old.has(d.identity)), removed = before.filter(d => !now.has(d.identity));
    for (const d of added) counts[d.rule].added++;
    for (const d of removed) counts[d.rule].removed++;
    return {counts, added, removed, accepted: added.length === 0 && (stage < 6 || after.length === 0)};
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
    const known = JSON.parse(blob(head,'tools/ui-consolidation/stage.json'));
    requireThat(known.schemaVersion === 1 && Array.isArray(known.knownSteps), 'Stage metadata');
    const meta = command === 'check' ? JSON.parse(fs.readFileSync(o['--completed-steps'],'utf8')) :
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
        const codeSha256 = digest(JSON.stringify([code.trim().replace(/\s+/g,' '), marker - (code.length-code.trimStart().length), ordinal]));
        const identity = JSON.stringify([issue.id,file,pkg+'.'+symbol,codeSha256]);
        return {rule:issue.id,path:file,fqn:pkg+'.'+symbol,codeSha256,originalLine:+loc.line,identity};
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
        const receipt = JSON.parse(blob(head,'tools/ui-consolidation/ui-lint-bootstrap.json'));
        fullSha(receipt.sourceSha);
        requireThat(receipt.integratedSha === known.integratedSha && receipt.approvedBy === 'lead' && receipt.approval === known.bootstrapApproval &&
            !exists(receipt.integratedSha,o['--baseline']) && receipt.baselineSha256 === digest(blob(receipt.sourceSha,o['--baseline'])), 'Invalid bootstrap receipt');
        requireThat(base === receipt.integratedSha || git('merge-base',base,receipt.integratedSha).trim() === base, 'Bootstrap base not pinned main ancestor');
        before = diagnostics(receipt.sourceSha);
    }
    const result = compare(before,after,stage);
    const report = {schemaVersion:1,command,baseSha:base,headSha:head,registrySha256:registry,
        baselineSha256:digest(blob(head,o['--baseline'])),manifestSha256:digest(manifestText),stage,completedSteps:meta.steps,
        perRule:result.counts,unmatchedIdentities:result.added,escapeCounts:{before:base && exists(base,o['--escapes']) ? manifest(blob(base,o['--escapes']),meta,known.knownSteps).length : 0,after:escapes.length},
        validationErrors:[],accepted:(command === 'bootstrap' || result.accepted) && escapes.length <= 5};
    fs.mkdirSync(o['--out'],{recursive:true}); fs.writeFileSync(path.join(o['--out'],'report.json'),JSON.stringify(report,null,2)+'\n');
    if (bootstrap) fs.writeFileSync(path.join(o['--out'],'bootstrap.json'),JSON.stringify(bootstrap,null,2)+'\n');
    return report.accepted ? 0 : 1;
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    try { process.exitCode = run(process.argv.slice(2)); } catch (e) { console.error(e.message); process.exitCode = 2; }
}
