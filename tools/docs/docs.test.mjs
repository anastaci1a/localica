import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {root,words,parser,proseErrors,outputPath,check} from './docs.mjs';

test('prose limits count visible labels and catch lazy list continuations',()=>{
 assert.equal(words('[two words](https://example.com/one) `value`'),3);
 assert.ok(proseErrors('# Title\n\n'+Array(60).fill('word').join(' '),'x.md').length);
 assert.ok(proseErrors('# Title\n\n- '+Array(30).fill('word').join(' '),'x.md').length);
 assert.ok(proseErrors('# Title\n\n- '+Array(20).fill('word').join(' ')+'\n'+Array(20).fill('word').join(' '),'x.md').length);
 assert.deepEqual(proseErrors('# Title\n\n```sh\nX=value\n```','x.md'),[]);
});
test('maintained pages allow 200 source lines, including blanks, but reject 201',()=>{
 const page=['# Title','','Overview.',...Array(196).fill(''),'End.'].join('\n');
 for(const ending of ['','\n']){
  assert.deepEqual(proseErrors(page+ending,'docs/page.md'),[]);
 }
 assert.ok(proseErrors(page+'\n\n','docs/page.md').some(e=>e.includes('201 source lines')));
 assert.ok(proseErrors((page+'\n\n').replaceAll('\n','\r\n'),'README.md').some(e=>e.includes('201 source lines')));
});
test('model pages open with a paragraph before lists, tables or subsections',()=>{
 for(const folder of ['architecture','interface','development']){
  const file=`docs/${folder}/page.md`;
  assert.deepEqual(proseErrors('# Title\n\nOverview.\n\n- Detail\n',file),[]);
  for(const block of ['- Detail','## Detail','```sh\nnpm test\n```','| A | B |\n| --- | --- |\n| C | D |','']){
   assert.ok(proseErrors('# Title\n\n'+block,file).some(e=>e.includes('overview paragraph')),`${file}: ${block}`);
  }
 }
 assert.deepEqual(proseErrors('# Title\n\n- Historical checklist','docs/qa/page.md'),[]);
 assert.deepEqual(proseErrors('# Title\n\n## Reference','docs/icon-packs.md'),[]);
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
