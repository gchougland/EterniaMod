import {landing} from './landing.js';
import {api,el,link,identityBar,notice} from './api.js';
import {treasury} from './treasury.js';
const view=document.querySelector('#view'),path=location.pathname;
document.querySelectorAll('nav a').forEach(a=>{if(a.pathname===path)a.setAttribute('aria-current','page');});
const state=await identityBar();view.replaceChildren();
function heading(kicker,title,description){view.append(el('div',kicker,'eyebrow'),el('h1',title));if(description)view.append(el('p',description,'muted'));}
function card(title,text){const c=el('article',undefined,'card');c.append(el('h3',title),el('p',text));return c;}
if(path==='/'){
 landing(view);
}else if(path==='/store'){
 await treasury(view,state);
}else{
 const titles={account:['Your account','Welcome back.'],owned:['Your collection','The things that make it yours.'],seasons:['Season passes','Your next chapter.']},kind=path.slice(1);heading(...(titles[kind]||titles.account));
 if(!state.user){view.append(el('div',state.authConfigured?'Sign in with Hytale to view your account.':'Account access will open after Hytale sign-in is configured.','empty'));}
 else try{const data=await api('/api/account');view.append(el('div',(data.source==='local-fixture'?'Local fixture · ':'')+'Updated '+new Date(data.updatedAt||Date.now()).toLocaleString(),'meta'));
 const grid=el('div',undefined,'grid');
 if(kind==='account'){const stats=data.stats||{},labels={playtimeHours:'Hours played',mobsDefeated:'Mobs defeated',resourcesGathered:'Resources gathered'};for(const[k,label]of Object.entries(labels)){const value=stats[k],formatted=value==null?'Not recorded':new Intl.NumberFormat(undefined,{maximumFractionDigits:k==='playtimeHours'?1:0}).format(value);const c=el('article',undefined,'card');c.append(el('div',formatted,'number'),el('p',label));grid.append(c);}if(data.coins?.available!=null)grid.append(card('Coins',new Intl.NumberFormat().format(data.coins.available)));view.append(el('p','Rank: '+(data.account?.rank||'Not available')));}
 if(kind==='owned')for(const item of data.owned||[])grid.append(card(item.name||item.id,item.kind==='quantity'?(item.quantity??0)+' available to use':item.kind==='capability'?'Access unlocked':item.kind==='unlock'?'Unlocked':(item.quantity??1)+' owned'));
 if(kind==='seasons')for(const season of data.seasons||[]){const c=card(season.name||season.id,(season.paid?'Free + paid track':'Free track')+' · Permanent');const bar=el('progress');bar.max=season.totalXp||1;bar.value=season.xp||0;bar.setAttribute('aria-label','Season experience');c.append(bar,el('p',(season.xp||0)+' / '+season.totalXp+' XP'));grid.append(c);}
 if(kind==='account'&&data.crowns)grid.append(card('Crowns',new Intl.NumberFormat().format(data.crowns.available)+(data.crowns.owed?' · '+data.crowns.owed+' to restore after a refund':'')));
 view.append(grid.children.length?grid:el('div','Nothing to show yet. Your progress appears here after your next adventure.','empty'));
 }catch(e){view.append(el('div',e.message,'empty'));}}
