// "Adventurers online" chip on the welcome page. Reads the website's cached count, never the game directly.
const REFRESH_MS=30000;
export function onlineText(data){
 if(data?.status!=='online'||!Number.isSafeInteger(data.online))return {state:'resting',text:'The realm is resting. Check back soon.'};
 if(data.online===0)return {state:'quiet',text:'The realm is quiet. Be the first to arrive.'};
 const count=new Intl.NumberFormat().format(data.online);
 return {state:'online',count,text:count+(data.online===1?' adventurer':' adventurers')+' online now'};
}
export function mountPlayersOnline(chip){
 if(!chip)return;
 const label=chip.querySelector('[data-online-text]');let timer;
 async function refresh(){
  let data;
  try{const res=await fetch('/api/v1/players-online',{cache:'no-store'});data=res.ok?await res.json():null;}catch{data=null;}
  const view=onlineText(data);
  chip.dataset.state=view.state;
  if(view.count){const strong=document.createElement('strong');strong.textContent=view.count;label.replaceChildren(strong,view.text.slice(view.count.length));}else label.textContent=view.text;
 }
 function schedule(){clearInterval(timer);timer=setInterval(()=>{if(!document.hidden)refresh();},REFRESH_MS);}
 document.addEventListener('visibilitychange',()=>{if(!document.hidden){refresh();schedule();}});
 refresh();schedule();
}
