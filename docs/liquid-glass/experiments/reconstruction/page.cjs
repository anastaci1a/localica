// Run after site.cjs; test guide layout and on-demand figures on desktop/phone.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'../..');
const out=process.env.LAB_EVIDENCE||path.join(root,'test-output/reconstruction');
const markdown=fs.readFileSync(path.join(root,'reconstruction.md'),'utf8');
const expectedImages=[...markdown.matchAll(/!\[[^\]]*\]\(([^)]+)\)|<img\b[^>]*src="([^"]+)"/g)].map(m=>m[1]||m[2]);
const expectedVideos=[...markdown.matchAll(/<source src="([^"]+\.mp4)"/g)].map(m=>m[1]);
assert(expectedVideos.length>=11,'Embed completed motion captures beside the comparisons');
for(const name of expectedImages.filter(s=>s.endsWith('.gif'))){
 const file=fs.openSync(path.join(root,name),'r'),header=Buffer.alloc(10);fs.readSync(file,header,0,10,0);fs.closeSync(file);
 assert(/^GIF8[79]a$/.test(header.toString('ascii',0,6))&&header.readUInt16LE(6)>0&&header.readUInt16LE(8)>0,'Invalid embedded GIF');
}
assert(expectedImages.length>=24,'Missing paired/progress comparisons');
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox']});
 const page=await browser.newPage();const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 for(const width of [1280,390]){
  await page.setViewportSize({width,height:950});
  await page.goto(pathToFileURL(path.join(root,'site/liquid-glass/reconstruction.html')).href);
  assert.equal(await page.locator('img').count(),expectedImages.length);
  const images=await page.locator('img').evaluateAll(list=>list.map(i=>({src:new URL(i.getAttribute('data-media-src')||i.src,location.href).pathname.split('/').pop(),width:+i.getAttribute('width'),height:+i.getAttribute('height')})));
  assert.deepEqual(images.map(i=>i.src),expectedImages.map(s=>s.split('/').pop()));
  for(const item of images.filter(i=>i.src.startsWith('study-')&&i.src.endsWith('.png')))assert(item.width>=1800&&item.height>=900,'New comparisons must retain HQ reconstruction panels');
  assert.equal(await page.locator('details:not([open]) img[src],details:not([open]) source[src]').count(),0,'Closed figures must have no active media URL');
  const videos=page.locator('video');assert.equal(await videos.count(),expectedVideos.length);
  for(const video of await videos.all()){
   assert(await video.evaluate(v=>v.controls&&v.preload==='none'&&!v.autoplay),'Motion must be opt-in');
   const item=await video.evaluate(v=>({source:new URL(v.querySelector('source').getAttribute('data-media-src'),location.href).pathname.split('/').pop(),width:v.width,height:v.height}));
   assert(expectedVideos.some(s=>s.endsWith(item.source))&&item.width>0&&item.height>0);
  }
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Page overflows');
  await page.screenshot({path:path.join(out,`page-${width}.png`)});
  for(const details of await page.locator('details').all()){
   await details.locator('summary').first().click();
   await page.waitForFunction(()=>[...document.querySelectorAll('details[open] img[data-media-src]')].every(i=>i.hasAttribute('src')));
   for(const img of await details.locator('img').all()){
    await img.evaluate(i=>i.decode());
    if((await img.getAttribute('src')).includes('-full.png'))assert.equal(await img.evaluate(i=>i.naturalWidth),3842);
   }
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Expanded figure overflows');
   await details.locator('summary').first().click();
   await page.waitForFunction(()=>!document.querySelector('details:not([open]) img[src],details:not([open]) source[src]'));
  }
  await page.locator('#scores').scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(out,`measurements-${width}.png`)});
  for(const section of ['apple-music-studies','active-switch--in-progress']){
   await page.locator('#'+section).scrollIntoViewIfNeeded();
   await page.screenshot({path:path.join(out,`${section}-${width}.png`)});
  }
 }
 for(const width of [1280,390]){
  await page.setViewportSize({width,height:950});
  await page.goto(pathToFileURL(path.join(root,'site/liquid-glass/recreation-models.html')).href);
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Mathematical specification overflows');
  await page.screenshot({path:path.join(out,`models-${width}.png`)});
 }
 await page.goto(pathToFileURL(path.join(root,'site/liquid-glass/reconstruction.html')).href);
 const video=page.locator('video').first();
 await video.evaluate(v=>{v.closest('details').open=true;});
 await page.waitForFunction(()=>!!document.querySelector('video source[src]'));
 await video.evaluate(async v=>{
  v.muted=true;v.preload='metadata';
  const loaded=new Promise((resolve,reject)=>{v.onloadedmetadata=resolve;v.onerror=()=>reject(Error('Video metadata failed'));});v.load();
  await Promise.race([loaded,new Promise((_,reject)=>setTimeout(()=>reject(Error('Video metadata timeout')),15000))]);
  if(!v.videoWidth||!v.videoHeight||!Number.isFinite(v.duration))throw Error('Invalid video metadata');
  v.closest('details').open=false;
 });
 await page.waitForFunction(()=>{const v=document.querySelector('video');return v.paused&&!v.querySelector('source').hasAttribute('src');});
 assert.deepEqual(errors,[]);await browser.close();
 console.log(`PASS desktop/phone layout, ${expectedImages.length} still/GIF references, ${expectedVideos.length} opt-in videos, on-demand figures and specification, no page errors`);
})().catch(e=>{console.error(e);process.exit(1)});
