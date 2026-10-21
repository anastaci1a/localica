// Verify compact entries, responsive image viewing and source/media preservation.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'../..'),out=process.env.LAB_EVIDENCE||path.join(root,'test-output/reconstruction');
const markdown=fs.readFileSync(path.join(root,'reconstruction.md'),'utf8');
const expected=[...markdown.matchAll(/!\[[^\]]*\]\(([^)]+)\)|<img\b[^>]*src="([^"]+)"/g)].map(m=>m[1]||m[2]);
(async()=>{
 fs.mkdirSync(out,{recursive:true});const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox']});
 try{
  const page=await browser.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  for(const width of [1280,390]){
   await page.setViewportSize({width,height:950});await page.goto(pathToFileURL(path.join(root,'site/liquid-glass/reconstruction.html')).href);
   assert.equal(await page.locator('details.study').count(),12);
   assert.equal(await page.locator('details.study#safari-circular-controls').count(),1,'Safari is a compact study, not an unbounded page section');
   const refs=await page.locator('main img:not(.study-thumbnail)').evaluateAll(list=>list.map(i=>(i.dataset.mediaSrc||i.getAttribute('src')).split('?')[0]));
   assert.deepEqual(refs,expected,'Retain every source image in order');
   assert.equal(await page.locator('details img[src]:not(.study-thumbnail)').count(),0);
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   await page.screenshot({path:path.join(out,`page-${width}.png`)});
   for(const card of await page.locator('details.study').all()){
    await card.evaluate(d=>d.open=true);
    const img=card.locator('img[data-media-src]').first();
    await img.evaluate(async i=>{while(!i.hasAttribute('src'))await new Promise(requestAnimationFrame);await i.decode();});
    await card.locator('.comparison').first().waitFor();
    const size=await card.locator('.comparison').first().boundingBox();assert(size.width<=900.1&&size.width<=width);
    assert.equal(await card.locator('details:not([open]) img[src]').count(),0,'Nested images remain deferred');
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Open card overflows');
    await card.evaluate(d=>d.open=false);
   }
   const playback=page.locator('#clear-playback-control');await playback.evaluate(d=>d.open=true);
   await playback.locator('.comparison').first().screenshot({path:path.join(out,`expanded-${width}.png`)});
   await page.goto(pathToFileURL(path.join(root,'site/liquid-glass/recreation-models.html')).href);
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Model page overflows');
  }
  assert.deepEqual(errors,[]);console.log(`PASS compact desktop/mobile entries; ${expected.length} source images retained; deferred nested figures and bounded widths`);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
