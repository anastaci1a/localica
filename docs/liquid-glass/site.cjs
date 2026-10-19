// Static Pages bundle. Only the public field guide is copied; no workspace data.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {execFileSync}=require('node:child_process');
(async()=>{
  const {Marked}=await import('marked');
  const root=__dirname,out=path.resolve(process.argv[2]||path.join(root,'site'));
  const dest=path.join(out,'liquid-glass');fs.mkdirSync(dest,{recursive:true});
  const source=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();
  const repo='https://github.com/anastaci1a/localica/blob/main/';
  const escape=s=>s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;');
  const slug=s=>s.replace(/<[^>]*>/g,'').toLowerCase().replace(/[^\p{L}\p{N}\s-]/gu,'').replace(/\s/g,'-');
  const pages=['README.md','sources.md','specification.md','controls.md','optics.md','model.md'];
  function link(href){
    if(!href||/^(https?:|#|mailto:)/.test(href))return href;
    const [file,hash]=href.split('#');
    if(file.startsWith('../'))return repo+path.posix.normalize('docs/liquid-glass/'+file)+(hash?'#'+hash:'');
    return (file==='README.md'?'index.html':file.replace(/\.md$/,'.html'))+(hash?'#'+hash:'');
  }
  const md=new Marked({renderer:{
    heading({tokens,depth}){const text=this.parser.parseInline(tokens);return `<h${depth} id="${slug(text)}">${text}</h${depth}>\n`;},
    link({href,title,tokens}){return `<a href="${escape(link(href))}"${title?` title="${escape(title)}"`:''}>${this.parser.parseInline(tokens)}</a>`;}
  }});
  const manifest={source,files:{}};
  function write(file,data){
    // Keep fresh HTML and its CSS, shader and captures on the same revision.
    if(file.endsWith('.html'))data=data.toString().replace(/(href|src)="((?!https?:|data:)[^"?]+\.(?:js|css|png|gif|svg))"/g,(_,attribute,url)=>`${attribute}="${url}?v=${source.slice(0,12)}"`);
    fs.mkdirSync(path.dirname(path.join(out,file)),{recursive:true});fs.writeFileSync(path.join(out,file),data);manifest.files[file]=crypto.createHash('sha256').update(data).digest('hex');}
  for(const name of pages){
    const content=md.parse(fs.readFileSync(path.join(root,name),'utf8'));
    write('liquid-glass/'+(name==='README.md'?'index.html':name.replace('.md','.html')),
      `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Localica · ${name==='README.md'?'Liquid Glass':name.replace('.md','')}</title><link rel="stylesheet" href="lab.css"><link rel="stylesheet" href="guide.css"></head><body class="guide"><nav><a href="index.html">Field guide</a><a href="lab.html">Live lab</a><a href="optics.html">Optical evidence</a><a href="model.html">Equations</a></nav><main>${content}</main><footer>Source <a href="https://github.com/anastaci1a/localica/tree/${source}/docs/liquid-glass">${source.slice(0,8)}</a> · Original studies; no claim of iOS parity.</footer></body></html>`);
  }
  for(const name of ['lab.html','lab.js','model.js','software.js','lab.css','guide.css'])write('liquid-glass/'+name,fs.readFileSync(path.join(root,name)));
  // Prune removed public media when rebuilding an existing Pages checkout.
  const assetDest=path.join(dest,'assets');
  if(fs.existsSync(assetDest))for(const name of fs.readdirSync(assetDest))if(/\.(png|gif|svg)$/.test(name)&&!fs.existsSync(path.join(root,'assets',name)))fs.unlinkSync(path.join(assetDest,name));
  for(const name of fs.readdirSync(path.join(root,'assets')))if(/\.(png|gif|svg)$/.test(name))write('liquid-glass/assets/'+name,fs.readFileSync(path.join(root,'assets',name)));
  write('liquid-glass/experiments/reference_patch.py',fs.readFileSync(path.join(root,'experiments/reference_patch.py')));
  write('liquid-glass/experiments/context-sources.json',fs.readFileSync(path.join(root,'experiments/context-sources.json')));
  write('liquid-glass/experiments/model-fixtures.json',fs.readFileSync(path.join(root,'experiments/model-fixtures.json')));
  write('.nojekyll','');
  write('index.html','<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="0;url=liquid-glass/lab.html"><title>Localica Liquid Glass lab</title><a href="liquid-glass/lab.html">Open the Liquid Glass lab</a></html>');
  fs.writeFileSync(path.join(out,'build.json'),JSON.stringify(manifest,null,2)+'\n');
  console.log(`Built ${Object.keys(manifest.files).length} public files at ${out}; source ${source}`);
})().catch(e=>{console.error(e);process.exit(1)});
