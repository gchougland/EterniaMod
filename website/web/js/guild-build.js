// Scroll scrubs a locally rendered native prefab. No WebGL, trackers, or game asset downloads.
export function mountGuildBuild(section){
 const canvas=section.querySelector('canvas'),ctx=canvas.getContext('2d'),label=section.querySelector('[data-build-label]'),toggle=section.querySelector('button');
 if(!ctx){section.dataset.fallback='true';return;}
 const reduce=matchMedia('(prefers-reduced-motion: reduce)'),saveData=navigator.connection?.saveData;
 const frames=[],count=48;let ready=false,visible=false,still=false,current=0,raf=0,loading=false;
 function target(){const r=section.getBoundingClientRect();return Math.max(0,Math.min(1,-r.top/Math.max(1,r.height-innerHeight)));}
 function paint(progress){const index=Math.round(progress*(count-1));if(!frames[index])return;
  ctx.clearRect(0,0,canvas.width,canvas.height);ctx.drawImage(frames[index],0,0,canvas.width,canvas.height);
  section.style.setProperty('--build-progress',progress);section.dataset.frame=String(index);
  label.textContent=progress<.2?'01 / Laying the foundations':progress<.5?'02 / Raising the walls':progress<.85?'03 / Making room for everyone':'04 / Welcome to your guild hall';
 }
 function tick(){raf=0;if(!ready||!visible||reduce.matches||still)return;const to=target();current+= (to-current)*.22;if(Math.abs(to-current)<.002)current=to;paint(current);if(current!==to)raf=requestAnimationFrame(tick);}
 function schedule(){if(!raf&&ready&&visible&&!reduce.matches&&!still)raf=requestAnimationFrame(tick);}
 async function load(){if(loading||saveData||reduce.matches)return;loading=true;
  try{let next=0;await Promise.all(Array.from({length:4},async()=>{for(;;){const i=next++;if(i>=count)break;const image=new Image();image.src='/media/guild-build/'+String(i).padStart(2,'0')+'.webp';await image.decode();frames[i]=image;}}));
   ready=true;section.dataset.ready='true';toggle.hidden=false;current=target();paint(current);schedule();
  }catch{section.dataset.fallback='true';label.textContent='Eternia Guild Hall';}
 }
 const near=new IntersectionObserver(entries=>{if(entries.some(e=>e.isIntersecting)){void load();near.disconnect();}},{rootMargin:'500px'});near.observe(section);
 const observer=new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;if(visible)schedule();else{cancelAnimationFrame(raf);raf=0;}});observer.observe(section);
 addEventListener('scroll',schedule,{passive:true});addEventListener('resize',schedule,{passive:true});
 toggle.addEventListener('click',()=>{still=!still;toggle.textContent=still?'Follow the scroll':'See completed hall';toggle.setAttribute('aria-pressed',String(still));if(still)paint(1);else{current=target();schedule();}});
 reduce.addEventListener('change',()=>{if(reduce.matches){cancelAnimationFrame(raf);raf=0;section.dataset.ready='false';toggle.hidden=true;}else if(ready){section.dataset.ready='true';toggle.hidden=false;schedule();}else void load();});
 if(saveData)section.dataset.fallback='true';
}
