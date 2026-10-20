// Primary-only, asset-preserving comparison controls; run after site.cjs.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process'),{pathToFileURL}=require('node:url');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../..'),url=pathToFileURL(path.join(root,'site/liquid-glass/reconstruction.html')).href;
const output=process.env.LAB_EVIDENCE;
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox']});
 try{
  const page=await browser.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  for(const width of [1280,390]){
   await page.setViewportSize({width,height:950});await page.goto(url);
   assert.equal(await page.locator('img[data-comparison-top]').count(),18,'Enhance every primary A/B pair');
   assert.equal(await page.locator('details img[data-comparison-top]').count(),0,'Do not enhance dropdown media');
   assert.equal(await page.locator('img[src*="rim"][data-comparison-top]').count(),0,'Leave supplementary crop sheets unchanged');
   const image=page.locator('img[data-comparison-top]').first();await image.evaluate(i=>i.decode());
   const figure=page.locator('.comparison').first(),handle=figure.getByRole('slider'),frame=figure.locator('.comparison-frame');
   await handle.waitFor({state:'visible'});assert.equal(await handle.getAttribute('aria-valuenow'),'50');
   await handle.focus();await page.keyboard.press('ArrowRight');assert.equal(await handle.getAttribute('aria-valuenow'),'51');
   await page.keyboard.press('Shift+ArrowLeft');assert.equal(await handle.getAttribute('aria-valuenow'),'41');
   await page.keyboard.press('Home');assert.equal(await handle.getAttribute('aria-valuenow'),'0');
   await page.keyboard.press('End');assert.equal(await handle.getAttribute('aria-valuenow'),'100');
   // Pointer capture continues beyond the frame and clamps at both ends.
   const box=await frame.boundingBox();await page.mouse.move(box.x+box.width-4,box.y+box.height/2);await page.mouse.down();
   await page.mouse.move(box.x-30,box.y+box.height/2,{steps:8});assert.equal(await handle.getAttribute('aria-valuenow'),'0');
   await page.mouse.move(box.x+box.width*.75,box.y+box.height/2,{steps:8});await page.mouse.up();assert.equal(await handle.getAttribute('aria-valuenow'),'75');
   await figure.getByRole('button',{name:'Side by side'}).click();assert(await image.isVisible());assert(!(await handle.isVisible()));
   await figure.getByRole('button',{name:'Wipe',exact:true}).click();assert.equal(await handle.getAttribute('aria-valuenow'),'75');
   await handle.focus();await page.keyboard.press('Home');await page.keyboard.press('Shift+ArrowRight');await page.keyboard.press('Shift+ArrowRight');await page.keyboard.press('Shift+ArrowRight');await page.keyboard.press('Shift+ArrowRight');await page.keyboard.press('Shift+ArrowRight');
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   if(output){fs.mkdirSync(output,{recursive:true});await figure.screenshot({path:path.join(output,`wipe-${width}.png`)});}
   await page.reload();await page.locator('.comparison').first().waitFor();assert.equal(await page.getByRole('slider').first().getAttribute('aria-valuenow'),'50','Reload defaults to centered Wipe');
  }
  const mobile=await browser.newContext({viewport:{width:390,height:950},hasTouch:true,isMobile:true});
  const touchPage=await mobile.newPage();await touchPage.goto(url);const touchFrame=touchPage.locator('.comparison-frame').first();await touchFrame.scrollIntoViewIfNeeded();
  const rect=await touchFrame.boundingBox(),cdp=await mobile.newCDPSession(touchPage),y=rect.y+rect.height/2;
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:rect.x+rect.width*.5,y}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:rect.x+rect.width*.7,y}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  assert.equal(await touchPage.getByRole('slider').first().getAttribute('aria-valuenow'),'70','Touch drag follows the divider');await mobile.close();
  // Synthetic colors make an incorrect half/gutter/header crop unambiguous.
  const svg='<svg xmlns="http://www.w3.org/2000/svg" width="22" height="10"><path fill="black" d="M0 0h22v10H0z"/><path fill="red" d="M0 2h10v8H0z"/><path fill="blue" d="M12 2h10v8H12z"/></svg>';
  await page.setContent(`<style>${fs.readFileSync(path.join(root,'guide.css'),'utf8')} .comparison{width:200px}</style><img data-comparison-top="2" alt="Crop fixture" src="data:image/svg+xml,${encodeURIComponent(svg)}">`);
  await page.locator('img').evaluate(i=>i.decode());await page.addScriptTag({content:fs.readFileSync(path.join(root,'guide-comparison.js'),'utf8')});
  const fixture=page.locator('.comparison-frame'),png=await fixture.screenshot();
  const colors=JSON.parse(execFileSync('python3',['-c','import sys,json,io;from PIL import Image;im=Image.open(io.BytesIO(sys.stdin.buffer.read())).convert("RGB");print(json.dumps([im.getpixel((int(im.width*x),int(im.height*.25))) for x in [.2,.8]]))'],{input:png,env:{...process.env,PYTHONPATH:'/tmp/optics-deps'}}));
  assert.deepEqual(colors,[[255,0,0],[0,0,255]],'Crop caption/gutter and align corresponding source halves');
  const noJS=await browser.newContext({javaScriptEnabled:false}),plain=await noJS.newPage();await plain.goto(url);
  assert.equal(await plain.locator('.comparison').count(),0);assert(await plain.locator('img').first().isVisible(),'No-JS original pair remains available');await noJS.close();
 assert.deepEqual(errors,[]);console.log('PASS18 primary pairs; desktop/phone, drag bounds, keyboard, reload, exact half/header/gutter crop, dropdown exclusion and no-JS fallback');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
