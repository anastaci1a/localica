// URLs inside closed details are assigned only when that section is opened.
const fs=require('node:fs'),path=require('node:path');
function prepareMedia(html,root){
 const sections=[];
 return html.replace(/<!--[\s\S]*?-->|<\/?[a-z][^>"']*(?:(?:"[^"]*"|'[^']*')[^>"']*)*>/gi,tag=>{
  if(/^<!--/.test(tag))return tag;
  if(/^<\/details\b/i.test(tag)){sections.pop();return tag;}
  if(/^<details\b/i.test(tag)){sections.push(!/\sopen(?:\s|=|>)/i.test(tag));return tag;}
  if(!/^<(img|video|source)\b/i.test(tag))return tag;
  if(/^<img\b/i.test(tag)){
   if(!/\sloading=/.test(tag))tag=tag.replace(/>$/,' loading="lazy">');
   if(!/\sdecoding=/.test(tag))tag=tag.replace(/>$/,' decoding="async">');
   const src=tag.match(/\ssrc="([^"]+)"/);
   if(src&&!/^(https?:|data:)/.test(src[1])&&!/\swidth=/.test(tag)){
    const file=path.resolve(root,src[1].split('?')[0]);
    if(!file.startsWith(root+path.sep))throw Error('Image outside public guide');
    const fd=fs.openSync(file,'r'),header=Buffer.alloc(24);
    try{fs.readSync(fd,header,0,24,0);}finally{fs.closeSync(fd);}
    let size;
    if(header.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))size=[header.readUInt32BE(16),header.readUInt32BE(20)];
    else if(/^GIF8[79]a$/.test(header.toString('ascii',0,6)))size=[header.readUInt16LE(6),header.readUInt16LE(8)];
    if(size)tag=tag.replace(/>$/,` width="${size[0]}" height="${size[1]}">`);
   }
  }
  return sections.length?tag.replace(/\s(src|poster)="([^"]+)"/g,' data-media-$1="$2"'):tag;
 });
}
module.exports={prepareMedia};
