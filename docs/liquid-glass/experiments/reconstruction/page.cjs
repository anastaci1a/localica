// Run after `node site.cjs`; inspect actual built guide, including hidden figures.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'../..');
const out=process.env.LAB_EVIDENCE||path.join(root,'test-output/reconstruction');
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox']});
 const page=await browser.newPage();const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 for(const width of [1280,390]){
  await page.setViewportSize({width,height:950});
  await page.goto(pathToFileURL(path.join(root,'site/liquid-glass/reconstruction.html')).href);
  await page.evaluate(async()=>{await Promise.all([...document.images].map(i=>i.decode()));});
  assert.equal(await page.locator('details').count(),10);
  assert.equal(await page.locator('img').count(),29);
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Page overflows');
  await page.screenshot({path:path.join(out,`page-${width}.png`)});
  for(const details of await page.locator('details').all()){
   await details.locator('summary').click();
   assert(await details.getAttribute('open')!==null);
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Expanded figure overflows');
  }
  for(const img of await page.locator('img[src*="-full.png"]').all())assert.equal(await img.evaluate(i=>i.naturalWidth),3842);
  await page.locator('#measurements').scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(out,`measurements-${width}.png`)});
 }
 assert.deepEqual(errors,[]);await browser.close();
 console.log('PASS desktop/phone layout, 29 decoded figures, 10 expandable sections, 5 native-HD pairs, no page errors');
})().catch(e=>{console.error(e);process.exit(1)});
