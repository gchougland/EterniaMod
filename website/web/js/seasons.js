import {api,el} from './api.js';

// Season passes in the same Codex layout as the in-game Journal: passes on the left, the open pass and its reward track on the right.
const ICONS={prop:'furnishing',addition:'addition',house:'housing',title:'title',wearable:'wardrobe',outfit:'wardrobe',pet:'pet',mount:'mount',palette:'palette',path:'path',plot_move_credit:'move',move:'move',season:'season',season_paid:'season',guild_voucher:'charter',convenience:'worlds',currency:'crown',crowns:'crown'};
const number=new Intl.NumberFormat();
const key=id=>String(id||'').replace(/^eternia:/,'').replace(/^season\//,'');
const pretty=id=>key(id).split('/').pop().replace(/[_-]+/g,' ').replace(/\b\w/g,c=>c.toUpperCase());

function picture(contentId,alt){
 const [kind,id]=key(contentId).split('/');const img=el('img');img.alt=alt;img.loading='lazy';img.width=72;img.height=72;
 const fallback='/media/catalog/icons/'+(ICONS[kind]||'collection')+'.svg';
 img.src=id?'/media/catalog/'+(kind==='addition'?'prop':kind)+'/'+id+'.png':fallback;
 img.onerror=()=>{img.onerror=null;img.src=fallback;};return img;
}
function icon(name,size){const img=el('img');img.src='/media/catalog/icons/'+name+'.svg';img.alt='';img.width=size;img.height=size;return img;}

function levelOf(pass,progress){
 if(progress?.level!=null)return progress.level;
 const xp=progress?.xp||0;return (pass.thresholds||[]).filter(t=>t<=xp).length;
}

function rewardCard(reward,track,progress,level){
 const card=el('div',undefined,'reward-card '+track);
 if(!reward){card.classList.add('empty-slot');card.append(el('span','No reward at this level','muted'));return card;}
 const name=(reward.name||pretty(reward.contentId))+(reward.quantity>1?' × '+reward.quantity:'');
 const art=el('div',undefined,'reward-art');art.append(picture(reward.contentId,name));
 const text=el('div',undefined,'reward-text');text.append(el('span',track==='paid'?'Premium':'Free','reward-track'),el('strong',name));
 let state='Level '+reward.level,cls='';
 if(progress){
  if(track==='paid'&&!progress.paid){state='Premium pass';cls='locked';}
  else if(level>=reward.level){state='Reached';cls='reached';}
  else state='Reach level '+reward.level;
 }
 text.append(el('span',state,'reward-state '+cls));card.append(art,text);if(cls)card.classList.add(cls);return card;
}

function passView(pass,progress){
 const panel=el('article',undefined,'codex-panel pass-view');
 const level=levelOf(pass,progress),head=el('header',undefined,'pass-head');
 const emblem=el('div',undefined,'emblem');emblem.append(icon('season',44));
 const titles=el('div');titles.append(el('div','Season pass','eyebrow'),el('h2',pass.name));if(pass.description)titles.append(el('p',pass.description,'muted'));
 head.append(emblem,titles);
 if(progress){const badge=el('div',undefined,'pass-level');badge.append(el('span',String(level),'number'),el('span','of '+pass.levels,'muted'));head.append(badge);}
 panel.append(head);
 if(progress){
  const xp=progress.xp||0,next=(pass.thresholds||[])[level],bar=el('div',undefined,'pass-bar'),fill=el('span');
  fill.style.width=Math.min(100,Math.round(xp/Math.max(1,pass.totalXp)*100))+'%';bar.append(fill);bar.setAttribute('role','img');bar.setAttribute('aria-label',number.format(xp)+' of '+number.format(pass.totalXp)+' season points');
  const row=el('div',undefined,'pass-bar-text');row.append(el('span',number.format(xp)+' season points'),el('span',next?number.format(next-xp)+' to level '+(level+1):'Every level reached'));
  const meta=el('div',undefined,'pass-progress');meta.append(bar,row);
  meta.append(el('p',progress.paid?'Both tracks are yours.':'The free track is yours. A Premium pass unlocks the second track.','muted'));
  panel.append(meta);
 }else panel.append(el('p','Sign in with Hytale to see your level and progress on this pass.','pass-signin'));
 const tracks=el('div',undefined,'track-head');tracks.append(el('span','Level'),el('span','Free track','free'),el('span','Premium track','paid'));panel.append(tracks);
 const byLevel=new Map();for(const r of pass.rewards||[]){const tier=byLevel.get(r.level)||{free:[],paid:[]};tier[r.track==='PAID'?'paid':'free'].push(r);byLevel.set(r.level,tier);}
 const rows=el('ol',undefined,'reward-rows');
 for(const lv of [...byLevel.keys()].sort((a,b)=>a-b)){const tier=byLevel.get(lv);for(let i=0;i<Math.max(tier.free.length,tier.paid.length);i++){
  const row=el('li',undefined,'reward-row'+(progress&&level>=lv?' reached':'')+(i>0?' repeat':''));
  const label=el('div',undefined,'reward-level');label.append(el('span','Level'),el('strong',String(lv)));
  row.append(label,rewardCard(tier.free[i],'free',progress,level),rewardCard(tier.paid[i],'paid',progress,level));rows.append(row);
 }}
 panel.append(rows.children.length?rows:el('p','Rewards for this pass have not been published yet.','muted'));
 panel.append(el('p','Every season pass stays available forever. Earn points through quests and everyday play in Eternia.','pass-footnote'));
 return panel;
}

// Cloth colours for successive passes, so each banner reads as its own chapter.
const CLOTH=['teal','wine','night','moss'];

function banner(pass,index,progress,link){
 const tag=link?'a':'div',b=el(tag,undefined,'pass-banner cloth-'+CLOTH[index%CLOTH.length]);
 if(link){b.href='/seasons?pass='+encodeURIComponent(key(pass.id));b.setAttribute('aria-label','Open '+pass.name);}
 const cloth=el('div',undefined,'banner-cloth'),seal=el('div',undefined,'banner-seal');seal.append(icon('season',52));
 cloth.append(el('span','Season pass','banner-kicker'),seal,el('h2',pass.name));
 if(progress){
  const level=levelOf(pass,progress),bar=el('div',undefined,'banner-bar'),fill=el('span');
  fill.style.width=Math.min(100,Math.round((progress.xp||0)/Math.max(1,pass.totalXp)*100))+'%';bar.append(fill);
  cloth.append(el('strong','Level '+level+' of '+pass.levels,'banner-level'),bar);
  const tags=el('div',undefined,'banner-tags');if(progress.active)tags.append(el('span','Active','banner-tag'));if(progress.paid)tags.append(el('span','Premium','banner-tag gold'));cloth.append(tags);
 }else cloth.append(el('strong',pass.levels+' levels','banner-level'));
 if(link)cloth.append(el('span','View pass','banner-cta'));
 b.append(el('span',undefined,'banner-rod'),cloth);return b;
}

export async function seasons(view,state){
 let passes=[];try{passes=(await (await fetch('/api/v1/seasons')).json()).seasons||[];}catch{/* Shown below as an empty state. */}
 const progress=new Map();let error;
 if(state.user)try{for(const s of (await api('/api/account')).seasons||[])progress.set(key(s.id),s);}catch(e){error=e.message;}
 const mine=pass=>state.user?progress.get(key(pass.id))||{xp:0,paid:false}:null;
 const wanted=new URLSearchParams(location.search).get('pass'),index=passes.findIndex(p=>key(p.id)===wanted);
 if(index<0){
  view.append(el('div','Season passes','eyebrow'),el('h1','Your next chapter.'),el('p','Choose a pass to see every reward. Passes never expire, and your progress on each one is kept.','muted'));
  if(error)view.append(el('div',error,'notice error'));
  if(!passes.length){view.append(el('div','Season passes will appear here when the first one opens.','empty'));return;}
  const row=el('div',undefined,'banner-row');passes.forEach((pass,i)=>row.append(banner(pass,i,mine(pass),true)));view.append(row);
  if(!state.user)view.append(el('p','Sign in with Hytale to see your level on each pass.','pass-signin'));
  return;
 }
 const pass=passes[index];
 view.append(el('div','Season passes','eyebrow'));
 if(error)view.append(el('div',error,'notice error'));
 const layout=el('section',undefined,'codex'),side=el('aside',undefined,'codex-side pass-side'),main=el('div',undefined,'codex-main');
 const back=el('a',undefined,'button pass-back');back.href='/seasons';back.append(icon('back',18),el('span','All passes'));
 const note=el('div',undefined,'side-note');note.append(icon('crown',28),el('span','Free and Premium tracks share your season points.'));
 side.append(back,banner(pass,index,mine(pass),false),note);
 main.append(passView(pass,mine(pass)));layout.append(side,main);view.append(layout);
 document.title=pass.name+' | Eternia';
}
