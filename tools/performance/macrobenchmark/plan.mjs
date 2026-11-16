import assert from 'node:assert/strict';
import {appendFileSync, readFileSync} from 'node:fs';
const known = JSON.parse(readFileSync(new URL('./scenarios.json', import.meta.url))).scenarios.map(s => s.id);
const selected = process.env.PERFORMANCE_SCENARIOS ? JSON.parse(process.env.PERFORMANCE_SCENARIOS) : known;
assert(Array.isArray(selected) && selected.length > 0 && selected.every(id => known.includes(id)));
assert.equal(new Set(selected).size, selected.length, 'Duplicate scenario');
const line = 'matrix=' + JSON.stringify({scenario: selected});
if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, line + '\n');
else console.log(line);
