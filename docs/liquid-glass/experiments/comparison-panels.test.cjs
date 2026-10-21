// Caption height is figure metadata, independent of optical rendering scale.
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {prepareMedia}=require('../guide-media.cjs');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'localica-comparison-'));
try{
 fs.mkdirSync(path.join(root,'assets'));
 const file='assets/study-example-paired-a.png',header=Buffer.alloc(24);
 Buffer.from([137,80,78,71,13,10,26,10]).copy(header);header.writeUInt32BE(2162,16);header.writeUInt32BE(346,20);fs.writeFileSync(path.join(root,file),header);
 const html=`<details><img src="${file}" alt="Apple and reconstruction"></details>`;
 assert.match(prepareMedia(html,root,{comparisons:true}),/data-comparison-top="128"/,'Unmodified historical figures retain the legacy convention');
 const metadata={[file]:{width:2162,height:346,top:64,bottom:0}};
 fs.writeFileSync(path.join(root,'comparison-panels.json'),JSON.stringify(metadata));
 const result=prepareMedia(html,root,{comparisons:true});
 assert.match(result,/data-comparison-top="64"/);assert.match(result,/data-media-src=/);assert.doesNotMatch(result,/<img[^>]*\ssrc=/);
 fs.writeFileSync(path.join(root,'assets/new-comparison.png'),header);
 metadata['assets/new-comparison.png']={width:2162,height:346,top:64,bottom:0};
 fs.writeFileSync(path.join(root,'comparison-panels.json'),JSON.stringify(metadata));
 assert.match(prepareMedia('<img src="assets/new-comparison.png">',root,{comparisons:true}),/data-comparison-top="64"/,'Declared figures do not depend on historical filenames');
 metadata[file].gutter=0;fs.writeFileSync(path.join(root,'comparison-panels.json'),JSON.stringify(metadata));
 assert.match(prepareMedia(html,root,{comparisons:true}),/data-comparison-gutter="0"/,'Zero separator width must not fall back to two');
 metadata[file].gutter=1;fs.writeFileSync(path.join(root,'comparison-panels.json'),JSON.stringify(metadata));
 assert.throws(()=>prepareMedia(html,root,{comparisons:true}),/Invalid comparison-panel gutter/);
 metadata[file].gutter=2;
 metadata[file].height=400;fs.writeFileSync(path.join(root,'comparison-panels.json'),JSON.stringify(metadata));
 assert.throws(()=>prepareMedia(html,root,{comparisons:true}),/Stale comparison-panel metadata/);
 console.log('PASS declared caption bounds, legacy compatibility, deferred loading and stale metadata rejection');
}finally{fs.rmSync(root,{recursive:true,force:true});}
