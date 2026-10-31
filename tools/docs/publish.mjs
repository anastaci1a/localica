import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
// The caller creates a disposable gh-pages worktree. No root-level file is replaced.
export function copySite(source,checkout) {
  source=path.resolve(source);checkout=path.resolve(checkout);
  if(!fs.existsSync(path.join(checkout,'.git')))throw new Error('Expected a Git checkout');
  const manifest=JSON.parse(fs.readFileSync(path.join(source,'manifest.json'),'utf8'));
  if(!manifest.pages?.includes('index.html'))throw new Error('Missing documentation manifest');
  const destination=path.join(checkout,'docs');
  if(source.startsWith(destination+path.sep)||source===destination)throw new Error('Source overlaps destination');
  if(fs.existsSync(destination)&&fs.lstatSync(destination).isSymbolicLink())throw new Error('Refusing symlink destination');
  fs.rmSync(destination,{recursive:true,force:true});
  fs.cpSync(source,destination,{recursive:true});
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))copySite(process.argv[2],process.argv[3]);
