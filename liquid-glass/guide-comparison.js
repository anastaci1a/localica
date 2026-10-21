// One viewer for existing source images; comparisons reuse the two PNG panels.
(()=>{
 const viewers=new Set(),dialog=document.createElement('dialog');
 dialog.className='image-dialog';dialog.setAttribute('aria-label','Image viewer');
 const close=document.createElement('button'),slot=document.createElement('div');
 close.className='image-dialog-close';close.textContent='Close';close.type='button';
 dialog.append(close,slot);document.body.append(dialog);
 let expanded=null;
 const visible=element=>{for(let d=element.closest('details');d;d=d.parentElement?.closest('details'))if(!d.open)return false;return true;};
 function dismiss(){if(dialog.open)dialog.close();}
 close.addEventListener('click',dismiss);
 dialog.addEventListener('click',e=>{if(e.target===dialog)dismiss();});
 dialog.addEventListener('close',()=>{
  if(!expanded)return;const v=expanded;expanded=null;
  v.marker.replaceWith(v.figure);v.marker=null;v.reset();v.full.focus({preventScroll:true});
  document.body.classList.remove('image-dialog-open');
 });
 for(const image of document.querySelectorAll('main img:not(.study-thumbnail),body>img[data-comparison-top]')){
  const initialize=()=>{
   if(image.dataset.viewerReady||!image.naturalWidth)return;
   image.dataset.viewerReady='true';
   const original=image.closest('a')||image,figure=document.createElement('figure');
   figure.className='comparison';figure.setAttribute('aria-label',image.alt);
   original.replaceWith(figure);figure.append(original);
   const tools=document.createElement('div');tools.className='comparison-tools';
   const full=document.createElement('button');full.type='button';full.className='image-expand';full.textContent='Fullscreen';full.setAttribute('aria-label',`Fullscreen: ${image.alt}`);
   tools.append(full);figure.prepend(tools);
   let reset=()=>{},sync=()=>{};
   if(image.hasAttribute('data-comparison-top')){
    const gutter=+(image.dataset.comparisonGutter??2),W=(image.naturalWidth-gutter)/2,top=+image.dataset.comparisonTop,bottom=+(image.dataset.comparisonBottom||0),H=image.naturalHeight-top-bottom;
    if(!Number.isInteger(W)||W<1||H<1)throw Error('Invalid comparison dimensions');
    const modes=document.createElement('div');modes.className='comparison-modes';modes.setAttribute('role','group');modes.setAttribute('aria-label','Comparison view');
    const wipe=document.createElement('button'),side=document.createElement('button');
    wipe.type=side.type='button';wipe.textContent='Wipe';side.textContent='Side by side';modes.append(wipe,side);tools.prepend(modes);
    const frame=document.createElement('div');frame.className='comparison-frame';frame.style.aspectRatio=`${W} / ${H}`;frame.style.setProperty('--image-ratio',W/H);
    const layers=[];
    for(const name of ['reconstruction','apple']){
     const layer=document.createElement('div');layer.className='comparison-layer comparison-'+name;
     layer.style.backgroundSize=`${image.naturalWidth/W*100}% ${image.naturalHeight/H*100}%`;
     layer.style.backgroundPosition=`${name==='apple'?'left':'right'} ${top/(top+bottom||1)*100}%`;frame.append(layer);layers.push(layer);
    }
    sync=()=>{const url=image.getAttribute('src');for(const layer of layers)layer.style.backgroundImage=url&&visible(image)?`url(${JSON.stringify(url)})`:'none';};
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
     update(50);figure.classList.toggle('comparison-wipe',isWipe);frame.hidden=labels.hidden=!isWipe;
     original.hidden=isWipe;wipe.setAttribute('aria-pressed',String(isWipe));side.setAttribute('aria-pressed',String(!isWipe));
    };
    reset=()=>setMode(true);
    wipe.addEventListener('click',()=>setMode(true));side.addEventListener('click',()=>setMode(false));
    const move=event=>{const box=frame.getBoundingClientRect();update((event.clientX-box.left)/box.width*100);};
    handle.addEventListener('pointerdown',event=>{
     if(!event.isPrimary||event.button!==0)return;
     dragging=event.pointerId;handle.classList.add('pointer-drag');handle.setPointerCapture(event.pointerId);handle.focus({preventScroll:true});move(event);event.preventDefault();
    });
    handle.addEventListener('pointermove',event=>{if(dragging===event.pointerId)move(event);});
    for(const event of ['pointerup','pointercancel','lostpointercapture'])handle.addEventListener(event,e=>{if(dragging===e.pointerId)dragging=null;});
    handle.addEventListener('blur',()=>handle.classList.remove('pointer-drag'));
    handle.addEventListener('keydown',event=>{
     handle.classList.remove('pointer-drag');const steps={ArrowLeft:-1,ArrowDown:-1,ArrowRight:1,ArrowUp:1};
     if(event.key in steps)update(split+steps[event.key]*(event.shiftKey?10:1));
     else if(event.key==='Home')update(0);else if(event.key==='End')update(100);else return;
     event.preventDefault();
    });
    tools.after(labels,frame);reset();sync();image.addEventListener('load',sync);
   }
   const v={figure,image,full,reset,sync,marker:null};viewers.add(v);
   full.addEventListener('click',()=>{
    reset();v.marker=document.createComment('Inline image position');figure.replaceWith(v.marker);slot.append(figure);expanded=v;
    document.body.classList.add('image-dialog-open');dialog.showModal();close.focus();
   });
  };
  if(image.complete)initialize();image.addEventListener('load',initialize);
 }
 document.addEventListener('toggle',event=>{
  if(event.target.tagName!=='DETAILS')return;
  for(const v of viewers)if(event.target.contains(v.figure)){v.reset();v.sync();}
 },true);
 // Animate study expansion without replacing native disclosure semantics.
 for(const study of document.querySelectorAll('details.study')){
  const summary=study.querySelector(':scope>summary'),body=study.querySelector(':scope>.study-body');let animation;
  summary.addEventListener('click',event=>{
   if(matchMedia('(prefers-reduced-motion: reduce)').matches)return;
   event.preventDefault();const wasOpen=study.open;
   const start=study.getBoundingClientRect().height;animation?.cancel();
   study.style.overflow='hidden';study.open=true;
   const end=wasOpen?summary.getBoundingClientRect().height:summary.getBoundingClientRect().height+body.getBoundingClientRect().height;
   animation=study.animate({height:[start+'px',end+'px']},{duration:220,easing:'cubic-bezier(.2,.7,.2,1)'});
   animation.onfinish=()=>{study.open=!wasOpen;study.style.overflow='';animation=null;};
  });
 }
 const reveal=()=>{
  const target=document.getElementById(decodeURIComponent(location.hash.slice(1)));if(!target)return;
  for(let d=target.closest('details');d;d=d.parentElement?.closest('details'))d.open=true;
  target.scrollIntoView({block:'start'});
 };
 window.addEventListener('hashchange',reveal);if(location.hash)reveal();
})();
