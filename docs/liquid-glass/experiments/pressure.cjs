/* Directional wall pressure is a selectable Localica hypothesis, not Apple data. */
const{chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
(async()=>{
  const root=path.resolve(__dirname,'..'),out=process.env.LAB_EVIDENCE||path.join(root,'test-output');fs.mkdirSync(out,{recursive:true});
  const url=process.env.LAB_URL||pathToFileURL(path.join(root,'site/liquid-glass/lab.html')).href;
  const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const results=[],errors=[];
  for(const software of [false,true])for(const side of [-1,1]){
    const page=await browser.newPage({viewport:{width:1100,height:950}});page.on('pageerror',e=>errors.push(e.message));const u=new URL(url);if(software)u.searchParams.set('fallback','1');await page.goto(u.href);await page.waitForFunction(()=>window.glassLab);
    await page.evaluate(()=>{glassLab.configure({wallModel:'directional',redistribution:.5,reduce:false});glassLab.settle();});
    await page.locator('#scene').scrollIntoViewIfNeeded();const box=await page.locator('#scene').boundingBox();
    await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();const held=await page.evaluate(()=>glassLab.advance(700));
    assert(held.geometry.z<.8,'Normal pressure decreases depth');
    await page.mouse.move(side<0?box.x-80:box.x+box.width+80,box.y+box.height/2,{steps:12});
    const pressed=await page.evaluate(()=>glassLab.advance(900));
    assert(pressed.geometry.z>held.geometry.z+.04,'Side pressure must transfer compression into depth');
    assert(pressed.geometry.wallDepthLog>.05);assert(Math.abs(pressed.geometry.volume-1)<1e-10);
    const edge=side<0?32:928;assert(Math.abs(pressed.geometry.x+side*pressed.geometry.w/2-edge)<.01);
    const baseline=await page.evaluate(()=>{glassLab.configure({wallModel:'planar'});return glassLab.info();});
    assert(Math.abs(baseline.geometry.w-pressed.geometry.w)<.001);assert(baseline.geometry.h>pressed.geometry.h);assert(baseline.geometry.z<pressed.geometry.z);
    await page.evaluate(()=>glassLab.configure({wallModel:'directional'}));
    await page.mouse.up();let minDepth=10,maxDepth=0;
    for(let i=0;i<80;i++){const v=await page.evaluate(()=>glassLab.advance(16));minDepth=Math.min(minDepth,v.geometry.z);maxDepth=Math.max(maxDepth,v.geometry.z);assert(Math.abs(v.geometry.volume-1)<1e-10);assert(Math.abs(v.geometry.x+side*v.geometry.w/2-edge)<.01);}
    const settled=await page.evaluate(()=>glassLab.settle());assert(Math.abs(settled.geometry.z-1)<.002);assert(Math.abs(settled.state.wallStrain)<.001);
    assert(maxDepth>1,'Released depth rebounds');await page.waitForTimeout(80);const a=await page.evaluate(()=>glassLab.info().frames);await page.waitForTimeout(100);assert.equal(await page.evaluate(()=>glassLab.info().frames),a);
    results.push({renderer:software?'software':'webgl',side,heldDepth:held.geometry.z,wallDepth:pressed.geometry.z,releaseRange:[minDepth,maxDepth]});await page.close();
  }
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'pressure-results.json'),JSON.stringify({results,errors},null,2));console.log('PASS both wall directions/renderers: normal vs lateral pressure, depth transfer, baseline comparison, fixed contact, volume proxy, release rebound and idle sleep');await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
