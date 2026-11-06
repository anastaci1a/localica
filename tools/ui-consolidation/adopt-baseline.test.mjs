import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {verifyArtifacts} from './adopt-baseline.mjs';
test('receipt adoption rejects a changed raw artifact or traversal',()=>{
    const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ui-baseline-artifact-'));
    try { fs.writeFileSync(path.join(dir,'receipt.json'),'changed');
        assert.throws(()=>verifyArtifacts(dir,[{path:'receipt.json',sha256:'a'.repeat(64)}]),/hash mismatch/);
        assert.throws(()=>verifyArtifacts(dir,[{path:'../receipt.json'}]),/path/);
    }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
