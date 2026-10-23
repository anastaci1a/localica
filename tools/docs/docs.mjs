import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {Marked} from 'marked';
import katex from 'katex';

export const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const repository='https://github.com/anastaci1a/localica';
export function inventory(base=root) {
  const walk=dir=>fs.readdirSync(path.join(base,dir),{withFileTypes:true}).flatMap(e=>{
    const name=path.posix.join(dir,e.name);
    if(e.isDirectory())return name==='docs/project-history'?[]:walk(name);
    return name.endsWith('.md')?[name]:[];
  });
  return [...fs.readdirSync(base).filter(x=>x.endsWith('.md')), ...walk('agents'), ...walk('docs'), ...walk('.github/ISSUE_TEMPLATE'), '.github/RELEASE_TEMPLATE.md'].sort();
}
export function outputPath(file) {
  if(file==='README.md')return 'index.html';
  if(file==='DOCUMENTATION.md')return 'standard.html';
  return file.replace(/^docs\//,'guide/').replace(/^agents\//,'contributors/').replace(/^\.github\//,'templates/').replace(/README\.md$/,'index.html').replace(/\.md$/,'.html');
}
export function slug(text) {
  return text.toLowerCase().replace(/<[^>]*>/g,'').replace(/\[([^\]]*)\]\([^)]*\)/g,'$1').replace(/[^\p{L}\p{N}_\-\s]/gu,'').replace(/ /g,'-');
}
export function words(text) {
  return text.replace(/\[([^\]]+)\]\([^)]*\)/g,'$1').replace(/<[^>]*>/g,'').trim().split(/\s+/).filter(Boolean).length;
}
export function parser() {
  const md=new Marked({gfm:true});
  md.use({extensions:[{name:'displayMath',level:'block',start:src=>src.indexOf('$$'),tokenizer(src){const m=/^\$\$\n([\s\S]*?)\n\$\$(?:\n|$)/.exec(src);if(m)return {type:'displayMath',raw:m[0],text:m[1]};},renderer(t){return katex.renderToString(t.text,{displayMode:true,throwOnError:true,strict:'error',trust:false})+'\n';}}]});
  return md;
}
export function proseErrors(source,file) {
  const errors=[];
  let content=source.replace(/^---\n[\s\S]*?\n---\n/,'').replace(/<!--[\s\S]*?-->/g,'');
  if(file==='DOCUMENTATION.md')content=content.replace(/## Required standard\n[\s\S]*?(?=\n## Scope and review)/,'');
  const visit=tokens=>{for(const t of tokens){
    if(t.type==='paragraph'&&words(t.text)>=60)errors.push(`paragraph has ${words(t.text)} words: ${t.text.slice(0,70)}`);
    if(t.type==='list')for(const item of t.items){if(words(item.text)>=30)errors.push(`bullet has ${words(item.text)} words: ${item.text.slice(0,70)}`);}
    if(t.type==='table')for(const row of t.rows)for(const cell of row)if(words(cell.text)>=60)errors.push(`table cell has ${words(cell.text)} words: ${cell.text.slice(0,70)}`);
    if(t.type==='blockquote')visit(t.tokens);
    if(t.type==='code'&&/[=∇∈]/.test(t.text)&&(!t.lang||t.lang==='text'))errors.push('mathematical code block needs display LaTeX');
  }};
  visit(parser().lexer(content));
  if(/\b(?:Ana|Anastacia|Anastaciia|Anastasia)\b/i.test(content.replace(/https?:\/\/[^\s)]+/g,'')))errors.push('personal name in maintained prose');
  if(/`[^`\n]*(?:\s=\s|∈|∇)[^`\n]*`/.test(content))errors.push('inline formula needs display LaTeX');
  return errors;
}
export function references(source) {
  const refs=[];const md=parser();md.walkTokens(md.lexer(source),t=>{if(t.type==='link'||t.type==='image')refs.push({href:t.href,image:t.type==='image',alt:t.text});});
  for(const m of source.matchAll(/<(?:a|img|source|video)\b[^>]*?\b(?:href|src|poster)=["']([^"']+)["'][^>]*>/g))refs.push({href:m[1],image:m[0].startsWith('<img'),alt:/\balt=["']([^"']*)/.exec(m[0])?.[1]});
  return refs;
}
function headings(source){const ids=new Set(),seen=new Map();for(const t of parser().lexer(source)){if(t.type==='heading'){const id=slug(t.text),n=seen.get(id)||0;seen.set(id,n+1);ids.add(id+(n?`-${n}`:''));}}return ids;}
function localTarget(file,href){const [location,hash]=href.split('#');return {target:location?path.posix.normalize(path.posix.join(path.posix.dirname(file),decodeURIComponent(location.split('?')[0]))):file,hash:hash?decodeURIComponent(hash):''};}
export function check(base=root){
 const files=inventory(base),errors=[];
 for(const file of files){const source=fs.readFileSync(path.join(base,file),'utf8');for(const e of proseErrors(source,file))errors.push(`${file}: ${e}`);
  const ts=parser().lexer(source.replace(/^---\n[\s\S]*?\n---\n/,''));const hs=ts.filter(t=>t.type==='heading');if(hs.filter(h=>h.depth===1).length!==1)errors.push(`${file}: expected one H1`);
  for(let i=1;i<hs.length;i++)if(hs[i].depth>hs[i-1].depth+1)errors.push(`${file}: skipped heading level: ${hs[i].text}`);
  for(const ref of references(source)){
   if(ref.image&&!ref.alt?.trim())errors.push(`${file}: image lacks description`);
   if(/^(?:https?:|mailto:)/.test(ref.href))continue;
   const {target,hash}=localTarget(file,ref.href);
   if(target.startsWith('../')||!fs.existsSync(path.join(base,target))){errors.push(`${file}: missing local target ${ref.href}`);continue;}
   if(hash&&target.endsWith('.md')&&!headings(fs.readFileSync(path.join(base,target),'utf8')).has(hash))errors.push(`${file}: missing fragment ${ref.href}`);
  }
 }
 return {files,errors};
}
function escape(s){return s.replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;').replaceAll('>','&gt;');}
export function build(base=root,destination=path.join(base,'build/docs-site/docs')){
 const {files,errors}=check(base);if(errors.length)throw new Error(errors.join('\n'));
 fs.mkdirSync(destination,{recursive:true});const written=[];
 const put=(name,data)=>{fs.mkdirSync(path.dirname(path.join(destination,name)),{recursive:true});fs.writeFileSync(path.join(destination,name),data);written.push(name);};
 const assets=new Set();
 for(const file of files){const md=parser(),seen=new Map(),out=outputPath(file),relative=name=>path.posix.relative(path.posix.dirname(out),name)||path.posix.basename(name);
  const rewrite=href=>{
   if(/^(?:https?:|mailto:)/.test(href))return href;
   const {target,hash}=localTarget(file,href);let dest;
   if(files.includes(target))dest=outputPath(target);
   else if(target.startsWith('docs/images/')){dest=target.replace('docs/','');assets.add(target);}
   else return `${repository}/blob/main/${target}${hash?'#'+hash:''}`;
   return relative(dest)+(hash?'#'+hash:'');
  };
  md.use({renderer:{heading(t){const id=slug(t.text),n=seen.get(id)||0;seen.set(id,n+1);return `<h${t.depth} id="${id}${n?'-'+n:''}">${this.parser.parseInline(t.tokens)}</h${t.depth}>\n`;},link(t){return `<a href="${escape(rewrite(t.href))}">${this.parser.parseInline(t.tokens)}</a>`;},image(t){return `<img src="${escape(rewrite(t.href))}" alt="${escape(t.text)}" loading="lazy">`;},html(t){return t.text.replace(/\b(href|src|poster)=["']([^"']+)["']/g,(_,a,h)=>`${a}="${escape(rewrite(h))}"`);}}});
  const source=fs.readFileSync(path.join(base,file),'utf8').replace(/^---\n[\s\S]*?\n---\n/,'');
  const title=/^# (.*)$/m.exec(source)?.[1]||file;
  put(out,`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)} · Localica</title><link rel="stylesheet" href="${relative('style.css')}"><link rel="stylesheet" href="${relative('math/katex.min.css')}"></head><body><a class="skip" href="#content">Skip to content</a><nav aria-label="Documentation"><a href="${relative('index.html')}">Localica</a><a href="${relative('guide/index.html')}">Documentation</a><a href="${relative('standard.html')}">Writing standard</a><a href="${relative('contributors/index.html')}">Contributing</a></nav><main id="content">${md.parse(source)}</main><footer><a href="${repository}/blob/main/${file}">Page source</a></footer></body></html>\n`);
 }
 for(const asset of assets)put(asset.replace('docs/',''),fs.readFileSync(path.join(base,asset)));
 const dist=path.join(root,'tools/docs/node_modules/katex/dist');put('math/katex.min.css',fs.readFileSync(path.join(dist,'katex.min.css')));
 for(const name of fs.readdirSync(path.join(dist,'fonts')))put('math/fonts/'+name,fs.readFileSync(path.join(dist,'fonts',name)));
 put('math/LICENSE',fs.readFileSync(path.join(root,'tools/docs/node_modules/katex/LICENSE')));
 put('style.css',`*{box-sizing:border-box}body{margin:0;background:#fafafa;color:#202124;font:17px/1.65 system-ui,sans-serif}main,nav,footer{max-width:76rem;margin:auto;padding:1.5rem}main{max-width:64rem}nav{display:flex;flex-wrap:wrap;gap:1rem;border-bottom:1px solid #ddd}a{color:#a3154b;text-underline-offset:.18em}h1,h2,h3{line-height:1.25;scroll-margin-top:1rem}h2{margin-top:2.5rem}h3{margin-top:1.8rem}p{max-width:80ch}table{display:block;overflow-x:auto;border-collapse:collapse;width:100%;margin:1.5rem 0}th,td{text-align:left;vertical-align:top;padding:.65rem;border:1px solid #ddd;min-width:9rem}th{background:#eee}pre{overflow:auto;padding:1rem;background:#eee;border-radius:.4rem}code{font-size:.9em;overflow-wrap:anywhere}img{max-width:100%;height:auto}details{padding:1rem;border:1px solid #ddd}summary{cursor:pointer}.katex-display{overflow:auto;padding:.5rem}.skip{position:absolute;left:-10000px}.skip:focus{position:static}footer{font-size:.85rem;border-top:1px solid #ddd}@media(max-width:600px){body{font-size:16px}main,nav,footer{padding:1rem}td{min-width:8rem}}`);
 put('manifest.json',JSON.stringify({source:execFileSync('git',['rev-parse','HEAD'],{cwd:base,encoding:'utf8'}).trim(),pages:files.map(outputPath)},null,2)+'\n');
 const broken=[];for(const file of written.filter(x=>x.endsWith('.html'))){const source=fs.readFileSync(path.join(destination,file),'utf8');for(const m of source.matchAll(/\b(?:href|src)="([^"]+)"/g)){
  const href=m[1];if(/^(https?:|mailto:)/.test(href))continue;
  const [p,hash]=href.split('#'),target=p?path.resolve(destination,path.dirname(file),p):path.join(destination,file);
  if(!fs.existsSync(target))broken.push(`${file}: ${href}`);
  else if(hash&&target.endsWith('.html')&&!fs.readFileSync(target,'utf8').includes(`id="${hash}"`))broken.push(`${file}: ${href}`);
 }}if(broken.length)throw new Error('Broken rendered links:\n'+broken.join('\n'));
 return {pages:files.length,files:written.length,destination};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 try{if(process.argv[2]==='build')console.log(build());else {const r=check();if(r.errors.length)throw new Error(r.errors.join('\n'));console.log(`Checked ${r.files.length} maintained Markdown files.`);}}catch(e){console.error(e.message);process.exitCode=1;}
}
