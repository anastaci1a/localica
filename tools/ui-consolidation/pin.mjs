import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
export function csv(text) {
    const rows = []; let row = [], value = '', quoted = false;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (c === '"') { if (quoted && text[i + 1] === '"') { value += c; i++; } else quoted = !quoted; }
        else if (!quoted && (c === ',' || c === '\n')) {
            row.push(value); value = '';
            if (c === '\n') { rows.push(row); row = []; }
        } else value += c;
    }
    if (quoted || row.length || value) throw Error('Malformed CSV');
    const keys = rows.shift();
    return rows.map(r => {
        if (r.length !== keys.length) throw Error('CSV column count');
        return Object.fromEntries(keys.map((k, i) => [k, r[i]]));
    });
}
export function identities(rows) {
    const keys = new Set();
    for (const r of rows) {
        const key = JSON.stringify([r.category, r.revision, r.file, r.line, r.pattern, r.normalized_column]);
        if (keys.has(key)) throw Error('Duplicate category+tuple');
        if (!/^[a-f0-9]{40}$/.test(r.revision) || !/^app\/src\/main\/.+\.kt$/.test(r.file) ||
            r.file.includes('..') || !r.category || !r.pattern || !Number.isInteger(+r.line) || !Number.isInteger(+r.normalized_column) || +r.line < 1 || +r.normalized_column < 1) throw Error('Invalid identity');
        keys.add(key);
    }
    return rows;
}
export function pin(args) {
    if (args.length % 2 || new Set(args.filter((_,i)=>i%2===0)).size !== args.length/2) throw Error('Invalid or duplicate pin arguments');
    const options = Object.fromEntries(Array.from({length: args.length / 2}, (_, i) => [args[i * 2], args[i * 2 + 1]]));
    for (const k of ['--source', '--historical', '--architecture', '--source-files', '--toolchain', '--out']) {
        if (!options[k]) throw Error(`Required ${k}; implicit HEAD is forbidden`);
    }
    const ref = options['--source'];
    if (!/^[a-f0-9]{40}$/.test(ref)) throw Error('Full immutable source SHA required');
    const git = (...a) => execFileSync('git', a, {maxBuffer: 64 * 1024 * 1024});
    const str = (...a) => git(...a).toString().trim();
    if (str('rev-parse', ref) !== ref) throw Error('Source mismatch');
    const rows = identities(csv(fs.readFileSync(options['--historical'], 'utf8')));
    const revisions = [...new Set(rows.map(r => r.revision))];
    if (revisions.length !== 1) throw Error('Mixed historical inventory');
    git('merge-base', '--is-ancestor', revisions[0], ref);
    const files = str('ls-tree', '-r', '--name-only', ref, 'app/src/main').split('\n').filter(f => f.endsWith('.kt'));
    const blobs = Object.fromEntries(files.map(f => [f, sha256(git('show', `${ref}:${f}`))]));
    const cache = new Map();
    const successors = [];
    const refreshed = rows.map(r => {
        if (!cache.has(r.file)) {
            const oldLines = git('show', `${r.revision}:${r.file}`).toString().split('\n');
            const current = blobs[r.file] ? git('show', `${ref}:${r.file}`).toString().split('\n') : [];
            cache.set(r.file, {oldLines, current});
        }
        const {oldLines, current} = cache.get(r.file);
        if (oldLines[+r.line - 1]?.trim() !== r.snippet) throw Error(`Historical snippet mismatch ${r.file}:${r.line}`);
        const matches = current.flatMap((line, i) => line.trim() === r.snippet ? [i + 1] : []);
        // Unique text successors are candidates, not proof of symbol migration or deletion credit.
        const same = current[+r.line - 1]?.trim() === r.snippet;
        successors.push({category:r.category,revision:r.revision,file:r.file,line:+r.line,pattern:r.pattern,normalized_column:+r.normalized_column,
            successorLine: same ? +r.line : matches.length===1 ? matches[0] : null,candidateLines:same ? [+r.line] : matches});
        return {id: Object.fromEntries(['revision', 'file', 'line', 'pattern', 'normalized_column'].map(k =>
            [k, ['line', 'normalized_column'].includes(k) ? +r[k] : r[k]])), category: r.category,
            status: !blobs[r.file] ? 'RemovedBeforeBaseline' : 'Open', ownerUnit: 'U14', stepId: null,
            successorFile: blobs[r.file] ? r.file : null, successorSymbol: null,
            causalSha: !blobs[r.file] ? ref : null,
            reason: !blobs[r.file] ? 'Absent at integrated pin; zero migration credit' : same ?
                'Same snippet/coordinate; ownership remains open' : matches.length === 1 ?
                'Unique snippet successor; symbol adoption remains open' : 'Changed or ambiguous source; requires adopter review',
            proofArtifacts: []};
    });
    refreshed.sort((a, b) => JSON.stringify(a.id).localeCompare(JSON.stringify(b.id)) || a.category.localeCompare(b.category));
    successors.sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b)));
    const out = options['--out'];
    if (fs.existsSync(out)) throw Error('Pin output must be a fresh directory');
    fs.mkdirSync(out, {recursive: true});
    const data = JSON.stringify(refreshed, null, 2) + '\n';
    const result = {schemaVersion: 1, inventoryRevision: revisions[0], integratedSha: ref,
        integratedTreeSha: str('rev-parse', `${ref}^{tree}`),
        architectureSha256: sha256(fs.readFileSync(options['--architecture'])),
        inventorySha256: sha256(fs.readFileSync(options['--historical'])),
        sourceFilesSha256: sha256(fs.readFileSync(options['--source-files'])),
        integratedInventorySha256: sha256(data), resolvedToolchainDigest: sha256(fs.readFileSync(options['--toolchain'])),
        predecessorMainSha: revisions[0], sourceBlobDigests: blobs,
        excludedPreexistingChangeShas: str('rev-list', `${revisions[0]}..${ref}`).split('\n').sort(), artifacts: []};
    fs.writeFileSync(path.join(out, 'rows.json'), data);
    fs.writeFileSync(path.join(out, 'successor-candidates.json'), JSON.stringify(successors,null,2)+'\n');
    fs.writeFileSync(path.join(out, 'pin.json'), JSON.stringify(result, null, 2) + '\n');
    fs.writeFileSync(path.join(out, 'refresh.json'), JSON.stringify({rows: rows.length, files: files.length,
        changedOrAmbiguous: successors.filter(r => !r.successorLine).length,
        removedBeforeBaseline: refreshed.filter(r => r.status === 'RemovedBeforeBaseline').map(r => r.id),
        claimedProductionSavings: 0}, null, 2) + '\n');
    return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    try { pin(process.argv.slice(2)); } catch (e) { console.error(e.message); process.exitCode = 2; }
}
