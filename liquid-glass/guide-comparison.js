// Align the two panels of existing comparison PNGs. No new image requests or
// rendering models: both layers reference the same already-decoded source URL.
(()=>{
 for(const image of document.querySelectorAll('img[data-comparison-top]')){
  if(image.closest('details'))continue;
  const initialize=()=>{
   if(image.dataset.comparisonReady||!image.naturalWidth)return;
   const W=(image.naturalWidth-2)/2,top=+image.dataset.comparisonTop,H=image.naturalHeight-top;
   if(!Number.isInteger(W)||W<1||H<1)return;
   image.dataset.comparisonReady='true';
   const original=image.closest('a')||image,figure=document.createElement('figure');
   figure.className='comparison';figure.setAttribute('aria-label',image.alt);
   original.replaceWith(figure);figure.append(original);
   const tools=document.createElement('div');tools.className='comparison-tools';
   const modes=document.createElement('div');modes.className='comparison-modes';modes.setAttribute('role','group');modes.setAttribute('aria-label','Comparison view');
   const wipe=document.createElement('button'),side=document.createElement('button');
   wipe.type=side.type='button';wipe.textContent='Wipe';side.textContent='Side by side';
   modes.append(wipe,side);tools.append(modes);
   const link=document.createElement('a');link.href=image.currentSrc||image.src;link.textContent='Full image';tools.append(link);
   const frame=document.createElement('div');frame.className='comparison-frame';frame.style.aspectRatio=`${W} / ${H}`;
   for(const name of ['reconstruction','apple']){
    const layer=document.createElement('div');layer.className='comparison-layer comparison-'+name;
    layer.style.backgroundImage=`url(${JSON.stringify(image.currentSrc||image.src)})`;
    layer.style.backgroundSize=`${image.naturalWidth/W*100}% ${image.naturalHeight/H*100}%`;
    layer.style.backgroundPosition=name==='apple'?'left bottom':'right bottom';frame.append(layer);
   }
   const labels=document.createElement('div');labels.className='comparison-labels';
   for(const text of ['Apple','Reconstruction']){const label=document.createElement('span');label.textContent=text;labels.append(label);}
   const handle=document.createElement('div');handle.className='comparison-handle';handle.tabIndex=0;
   for(const [key,value] of Object.entries({role:'slider','aria-label':'Comparison divider','aria-orientation':'horizontal','aria-valuemin':'0','aria-valuemax':'100'}))handle.setAttribute(key,value);
   const grip=document.createElement('span');grip.textContent='↔';grip.setAttribute('aria-hidden','true');handle.append(grip);frame.append(handle);
   let split=50,dragging=null;
   const update=value=>{
    split=Math.min(100,Math.max(0,value));frame.style.setProperty('--split',split+'%');
    handle.setAttribute('aria-valuenow',String(Math.round(split)));
    handle.setAttribute('aria-valuetext',`${Math.round(split)}% Apple, ${Math.round(100-split)}% reconstruction`);
   };
   const setMode=isWipe=>{
    figure.classList.toggle('comparison-wipe',isWipe);frame.hidden=labels.hidden=!isWipe;
    original.hidden=isWipe;wipe.setAttribute('aria-pressed',String(isWipe));side.setAttribute('aria-pressed',String(!isWipe));
   };
   wipe.addEventListener('click',()=>setMode(true));side.addEventListener('click',()=>setMode(false));
   const move=event=>{const box=frame.getBoundingClientRect();update((event.clientX-box.left)/box.width*100);};
   handle.addEventListener('pointerdown',event=>{
    if(!event.isPrimary||event.button!==0)return;
    dragging=event.pointerId;handle.setPointerCapture(event.pointerId);handle.focus({preventScroll:true});move(event);event.preventDefault();
   });
   handle.addEventListener('pointermove',event=>{if(dragging===event.pointerId)move(event);});
   const release=event=>{if(dragging===event.pointerId)dragging=null;};
   for(const event of ['pointerup','pointercancel','lostpointercapture'])handle.addEventListener(event,release);
   handle.addEventListener('keydown',event=>{
    const steps={ArrowLeft:-1,ArrowDown:-1,ArrowRight:1,ArrowUp:1};
    if(event.key in steps)update(split+steps[event.key]*(event.shiftKey?10:1));
    else if(event.key==='Home')update(0);else if(event.key==='End')update(100);else return;
    event.preventDefault();
   });
   figure.prepend(tools,labels,frame);update(50);setMode(true);
  };
  // Keep below-fold primaries lazy. Failed loads and disabled JS retain the
  // original image/link without dead controls or an empty comparison frame.
  if(image.complete)initialize();else image.addEventListener('load',initialize,{once:true});
 }
})();
