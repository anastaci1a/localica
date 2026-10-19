/* Context presets, optical independence and keyboard/mobile navigation.
   Actual framebuffer comparisons establish behavior, not Apple fidelity. */
const{chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
(async()=>{
  const root=path.resolve(__dirname,'..'),out=process.env.LAB_EVIDENCE||path.join(root,'test-output');fs.mkdirSync(out,{recursive:true});
  const url=process.env.LAB_URL||pathToFileURL(path.join(root,'site/liquid-glass/lab.html')).href;
  const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const results=[],errors=[];
  for(const software of [false,true]){
    const page=await browser.newPage({viewport:{width:1200,height:900}});page.on('pageerror',e=>errors.push(e.message));
    const target=new URL(url);if(software)target.searchParams.set('fallback','1');await page.goto(target.href);await page.waitForFunction(()=>window.glassLab);
    const configure=o=>page.evaluate(o=>{glassLab.configure(o);glassLab.settle();},o);
    const read=()=>page.evaluate(()=>Object.fromEntries([...document.querySelectorAll('input,select')].map(e=>[e.id,e.type==='checkbox'?e.checked:e.value])));
    await page.selectOption('#background','crossed');
    for(const preset of ['media','control','regularLight','regularDark','menu','extreme','previous']){
      await page.selectOption('#preset',preset);await page.evaluate(()=>glassLab.settle());const settings=await read();
      assert.equal(settings.preset,preset);assert.equal(settings.background,'crossed','Presets must preserve the comparison background');
      assert((await page.locator('#presetNote').textContent()).length>40);
      if(preset==='control')for(const k of ['blur','tint','trim','core'])assert.equal(+settings[k],0);
      if(preset==='regularDark')assert.equal(settings.ink,'dark');
      await page.locator('#stage').screenshot({path:path.join(out,`${software?'software':'gpu'}-${preset}.png`)});
    }
    // Quantized output samples: same background and no tone/illumination.
    const points=[[480,200],[480,157],[480,163],[480,237],[480,243],[385,200],[575,200]];
    const pixels=()=>page.evaluate(p=>glassLab.pixels(p),points);
    await configure({background:'coordinates',depth:1,shape:'pill',blur:0,tint:0,trim:0,shine:0,shadow:0,core:0,source:true});const identity=await pixels();
    await configure({source:false,model:'0',profile:'0',edge:.1});const old=await pixels();
    await configure({profile:'1',edge:.4});const rim=await pixels();
    assert.deepEqual(rim[0],identity[0],'Zero core keeps center registered');
    assert(rim[1][1]-identity[1][1]>old[1][1]-identity[1][1]+3,'Stronger near-rim bending must exist in actual output');
    assert(rim[4][1]<identity[4][1],'Lower rim bends symmetrically inward');
    // Edge control affects rim independently of central magnification.
    await configure({edge:0});const noEdge=await pixels();assert.deepEqual(noEdge,identity);
    await configure({core:.15});const core=await page.evaluate(()=>glassLab.pixel(510,200));
    await configure({core:0});const registered=await page.evaluate(()=>glassLab.pixel(510,200));assert(core[0]<registered[0]);
    results.push({renderer:software?'software':'webgl',nearTop:{identity:identity[1],previous:old[1],rim:rim[1]},centerUnchanged:true});
    // Native tab semantics; hidden panels stay out of keyboard traversal.
    await page.click('#tab-lens');assert.equal(await page.locator('#config-scene').isVisible(),false);
    await page.locator('#tab-lens').focus();await page.keyboard.press('ArrowRight');assert.equal(await page.locator('#tab-material').getAttribute('aria-selected'),'true');
    await page.keyboard.press('End');assert.equal(await page.locator('#tab-motion').getAttribute('aria-selected'),'true');
    await page.keyboard.press('Home');assert.equal(await page.locator('#tab-scene').getAttribute('aria-selected'),'true');
    await page.click('#tab-lens');await page.selectOption('#model','1');assert(await page.locator('#edge').isDisabled());assert(await page.locator('#core').isDisabled());assert(await page.locator('#profile').isDisabled());
    for(const model of ['0','1'])for(const shape of ['circle','pill','panel']){
      await configure({model,shape,depth:3,edge:1.5,core:.2,band:1,blur:8});const p=await pixels();assert(p.every(c=>c.every(Number.isFinite)));if(!software)assert.equal(await page.evaluate(()=>glassLab.error()),0);
    }
    await page.click('#reset');assert.equal((await read()).preset,'media');assert.equal(await page.locator('#tab-scene').getAttribute('aria-selected'),'true');
    await page.setViewportSize({width:390,height:844});await page.evaluate(()=>glassLab.settle());
    for(const tab of ['scene','lens','material','motion']){await page.click('#tab-'+tab);assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));}
    await page.screenshot({path:path.join(out,`${software?'software':'gpu'}-phone-tabs.png`),fullPage:true});
    await page.close();
  }
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'presets-results.json'),JSON.stringify({results,errors},null,2));
  console.log('PASS presets, independent optics, stronger rim, model applicability, keyboard tabs, extreme ranges and phone layout in WebGL and software');await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
