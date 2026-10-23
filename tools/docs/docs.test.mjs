import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {root,words,parser,proseErrors,outputPath,check} from './docs.mjs';
import {copySite} from './publish.mjs';

test('prose limits count visible labels and catch lazy list continuations',()=>{
 assert.equal(words('[two words](https://example.com/one) `value`'),3);
 assert.ok(proseErrors('# Title\n\n'+Array(60).fill('word').join(' '),'x.md').length);
 assert.ok(proseErrors('# Title\n\n- '+Array(30).fill('word').join(' '),'x.md').length);
 assert.ok(proseErrors('# Title\n\n- '+Array(20).fill('word').join(' ')+'\n'+Array(20).fill('word').join(' '),'x.md').length);
 assert.deepEqual(proseErrors('# Title\n\n```sh\nX=value\n```','x.md'),[]);
});
test('display math renders fractions, labeled subscripts, gradients and matrices',()=>{
 const html=parser().parse('$$\n\\nabla h=\\frac{x_{\\mathrm{rim}}}{2},\\quad \\mathbf{v}=\\begin{bmatrix}1\\\\2\\end{bmatrix}\n$$\n');
 assert.match(html,/<math/);assert.match(html,/<mfrac>/);assert.match(html,/<msub>/);assert.match(html,/<mtable/);assert.doesNotMatch(html,/katex-error/);
 assert.throws(()=>parser().parse('$$\n\\unknownCommand{x}\n$$\n'));
});
test('routes keep documentation within the owned site',()=>{
 assert.equal(outputPath('docs/README.md'),'guide/index.html');
 assert.equal(outputPath('agents/README.md'),'contributors/index.html');
 assert.equal(outputPath('docs/interface.md'),'guide/interface.html');
});
test('publication preserves unrelated deployed files',()=>{
 fs.mkdirSync(path.join(root,'build'),{recursive:true});
 const temp=fs.mkdtempSync(path.join(root,'build/publish-test-'));
 try{
  const checkout=path.join(temp,'pages'),source=path.join(temp,'source');
  fs.mkdirSync(path.join(checkout,'docs'),{recursive:true});fs.mkdirSync(source);
  fs.writeFileSync(path.join(checkout,'.git'),'gitdir: fixture');
  fs.writeFileSync(path.join(checkout,'index.html'),'existing root');
  fs.mkdirSync(path.join(checkout,'other'));fs.writeFileSync(path.join(checkout,'other/keep'),'unrelated');
  fs.writeFileSync(path.join(checkout,'docs/stale.html'),'old');
  fs.writeFileSync(path.join(source,'manifest.json'),JSON.stringify({pages:['index.html']}));
  fs.writeFileSync(path.join(source,'index.html'),'new docs');copySite(source,checkout);
  assert.equal(fs.readFileSync(path.join(checkout,'index.html'),'utf8'),'existing root');
  assert.equal(fs.readFileSync(path.join(checkout,'other/keep'),'utf8'),'unrelated');
  assert.equal(fs.readFileSync(path.join(checkout,'docs/index.html'),'utf8'),'new docs');
  assert.equal(fs.existsSync(path.join(checkout,'docs/stale.html')),false);
 }finally{fs.rmSync(temp,{recursive:true,force:true});}
});
test('all maintained source links and heading fragments resolve',()=>assert.deepEqual(check().errors,[]));

test('broken local files and fragments fail validation',()=>{
 fs.mkdirSync(path.join(root,'build'),{recursive:true});
 const temp=fs.mkdtempSync(path.join(root,'build/links-test-'));
 try{
  for(const dir of ['agents','docs','.github/ISSUE_TEMPLATE'])fs.mkdirSync(path.join(temp,dir),{recursive:true});
  fs.writeFileSync(path.join(temp,'.github/RELEASE_TEMPLATE.md'),'# Release\n');
  fs.writeFileSync(path.join(temp,'README.md'),'# Home\n\n[Missing](docs/missing.md) · [Bad anchor](docs/page.md#absent)\n');
  fs.writeFileSync(path.join(temp,'docs/page.md'),'# Page\n\n## Present\n');
  const errors=check(temp).errors.join('\n');
  assert.match(errors,/missing local target docs\/missing.md/);
  assert.match(errors,/missing fragment docs\/page.md#absent/);
 }finally{fs.rmSync(temp,{recursive:true,force:true});}
});
