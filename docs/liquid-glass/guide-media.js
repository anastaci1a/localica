// Closed figures retain their URLs as data, without starting image/media requests.
(()=>{
 const visible=element=>{
  for(let section=element.closest('details');section;section=section.parentElement?.closest('details'))if(!section.open)return false;
  return true;
 };
 const update=section=>{
  const videos=new Set();
  for(const element of section.querySelectorAll('[data-media-src],[data-media-poster]')){
   const open=visible(element);
   for(const attribute of ['src','poster']){
    const url=element.getAttribute('data-media-'+attribute);
    if(!url)continue;
    if(open&&!element.hasAttribute(attribute)){
     if(element.tagName==='IMG')element.loading='eager';
     element.setAttribute(attribute,url);
    }else if(!open&&element.hasAttribute(attribute))element.removeAttribute(attribute);
   }
   const video=element.tagName==='VIDEO'?element:element.closest('video');
   if(video)videos.add(video);
  }
  for(const video of videos){if(!visible(video))video.pause();video.load();}
 };
 document.addEventListener('toggle',event=>{if(event.target.tagName==='DETAILS')update(event.target);},true);
 for(const section of document.querySelectorAll('details[open]'))update(section);
})();
