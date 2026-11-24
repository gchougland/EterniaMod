import {el,identityBar,notice} from '/js/api.js';import {drawAll} from '/admin/analytics-charts.js';
await identityBar();
const REPORTS=[
 ['daily','Daily','One row per day: unique players, new and returning players, peak and average online, Crown Store orders and Tebex purchases.'],
 ['weekly','Weekly','The same by week, plus how many of each week\'s new players came back the next week and the week after.'],
 ['monthly','Monthly','The same by month, with retention into the next two months.'],
 ['players','Players','One row per player active or buying in the range: first and last seen, days played, playtime, Crowns spent and Tebex transactions.'],
 ['shop_items','Shop items','One row per Crown Store item sold in the range: orders, unique buyers, Crowns, and sales in its first 7 and 30 days.'],
 ['tebex_purchases','Tebex purchases','Every delivered Tebex purchase with its transaction ID, package and player, to match against the Tebex export.']];
const from=document.querySelector('#from'),to=document.querySelector('#to');
const iso=d=>d.toISOString().slice(0,10);
function last(days){const end=new Date();const start=new Date(end.getTime()-(days-1)*86400000);from.value=iso(start);to.value=iso(end);}
last(30);
for(const b of document.querySelectorAll('[data-days]'))b.onclick=()=>{last(Number(b.dataset.days));graphs();};
const graphRoot=document.querySelector('#graphs');let drawing=0,width=0;
async function graphs(){if(!from.value||!to.value||to.value<from.value)return;const mine=++drawing;
 try{await drawAll(graphRoot,from.value,to.value,()=>mine===drawing);}catch(e){if(mine===drawing)graphRoot.replaceChildren(el('p',e.message,'notice error'));}}
from.onchange=to.onchange=graphs;document.querySelector('#refresh').onclick=graphs;
// Redraw on a real width change so the graphs fit phones and wide screens alike.
let resize;new ResizeObserver(([e])=>{const w=Math.round(e.contentRect.width);if(Math.abs(w-width)<40)return;const first=!width;width=w;if(first)return;clearTimeout(resize);resize=setTimeout(graphs,250);}).observe(graphRoot);
graphs();
const grid=document.querySelector('#reports');
for(const [id,name,about] of REPORTS){const card=el('article',undefined,'card');const button=el('button','Download '+name.toLowerCase()+' CSV','primary');
 button.onclick=()=>download(id,button);card.append(el('h3',name),el('p',about,'muted'),button);grid.append(card);}
async function download(id,button){
 if(!from.value||!to.value){notice('Pick both dates first.',true);return;}
 if(to.value<from.value){notice('The end date is before the start date.',true);return;}
 button.disabled=true;const label=button.textContent;button.textContent='Building report…';
 try{const res=await fetch('/api/admin/analytics/'+id+'.csv?'+new URLSearchParams({from:from.value,to:to.value}),{cache:'no-store'});
  if(!res.ok){let why='Download failed';try{why=(await res.json()).error||why;}catch{}throw new Error(why);}
  const blob=await res.blob(),url=URL.createObjectURL(blob),a=document.createElement('a');
  a.href=url;a.download='eternia-'+id+'-'+from.value+'-to-'+to.value+'.csv';document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
  notice('Downloaded '+a.download+'.');
 }catch(e){notice(e.message,true);}finally{button.disabled=false;button.textContent=label;}
}
