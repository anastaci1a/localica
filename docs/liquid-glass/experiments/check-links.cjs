const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../site');
let checked=0;
function walk(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){const file=path.join(dir,entry.name);if(entry.isDirectory())walk(file);else if(file.endsWith('.html')){
 const text=fs.readFileSync(file,'utf8');for(const match of text.matchAll(/(?:href|src)="([^"]+)"/g)){
  if(/^(https?:|data:|mailto:)/.test(match[1]))continue;
  const [url,anchor]=match[1].split('#'),target=path.resolve(path.dirname(file),url||path.basename(file));
  assert(fs.existsSync(target),`${file}: missing ${match[1]}`);
  if(anchor&&target.endsWith('.html'))assert(fs.readFileSync(target,'utf8').includes(`id="${anchor}"`),`${file}: missing anchor ${match[1]}`);
  checked++;
 }
}}}
walk(root);console.log(`PASS ${checked} local Pages links/assets/anchors`);
