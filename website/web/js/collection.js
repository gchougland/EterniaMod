import {api,el} from './api.js';
import {picture} from './seasons.js';

// The same shelves as the in-game Journal Collection, in the same order. Internal grants match no shelf and stay hidden.
const SHELVES=[
 {id:'houses',label:'Houses',icon:'housing',kinds:['house'],title:'Your houses',text:'Homes you can build. Choose one for your plot from Housing storage in game.'},
 {id:'decorations',label:'Decorations',icon:'furnishing',kinds:['prop','addition'],title:'Your decorations',text:'Furniture, garden treasures and additions for your home.'},
 {id:'materials',label:'Materials',icon:'palette',kinds:['palette','path'],title:'Your materials',text:'Palettes and paths to make your home your own.'},
 {id:'titles',label:'Titles',icon:'title',kinds:['title'],title:'Your titles',text:'Pair a prefix and suffix to make your name your own.'},
 {id:'wardrobe',label:'Wardrobe',icon:'wardrobe',kinds:['wearable','outfit'],title:'Your wardrobe',text:'Looks to wear on your adventures.'},
 {id:'pets',label:'Pets',icon:'pet',kinds:['pet'],title:'Your pets',text:'Companions to follow you or roam around your home.'},
 {id:'mounts',label:'Mounts',icon:'mount',kinds:['mount'],title:'Your mounts',text:'Summon a mount in game to start riding.'},
 {id:'upgrades',label:'Upgrades',icon:'addition',kinds:['plot','plot_move_credit','convenience','guild_voucher','season_paid'],title:'Your upgrades',text:'Your plot upgrades, waygates and passes.'}
];
const number=new Intl.NumberFormat();
const kindOf=id=>String(id||'').replace(/^eternia:/,'').split('/')[0];
const shelfOf=item=>SHELVES.find(s=>s.kinds.includes(kindOf(item.id)));

function status(item){
 const parts=[item.kind==='quantity'?number.format(item.quantity??0)+' available':'Owned'];
 if(item.packed>0)parts.push(number.format(item.packed)+' packed');
 if(item.placed>0)parts.push(number.format(item.placed)+' placed');
 return parts.join(' • ');
}
function card(item){
 const c=el('article',undefined,'collection-card'),art=el('div',undefined,'collection-art'),name=item.name||item.id;
 if(item.kind==='quantity'&&!item.quantity&&!item.packed&&!item.placed)c.classList.add('used-up');
 art.append(picture(item.id,name));c.append(art,el('h3',name),el('p',status(item),'muted'));
 return c;
}

export async function collection(view,state){
 view.append(el('div','Your collection','eyebrow'),el('h1','The things that make it yours.'));
 if(!state.user){view.append(el('div',state.authConfigured?'Sign in with Hytale to view your collection.':'Your collection will open after Hytale sign-in is configured.','empty'));return;}
 let data;try{data=await api('/api/account');}catch(e){view.append(el('div',e.message,'empty'));return;}
 const shelves=new Map(SHELVES.map(s=>[s.id,[]]));
 for(const item of data.owned||[]){const shelf=shelfOf(item);if(shelf)shelves.get(shelf.id).push(item);}
 for(const items of shelves.values())items.sort((a,b)=>(a.name||a.id).localeCompare(b.name||b.id));

 const layout=el('div',undefined,'codex collection'),side=el('nav',undefined,'codex-panel collection-side'),panel=el('section',undefined,'codex-panel collection-shelf');
 side.setAttribute('aria-label','Collection categories');panel.id='shelf';
 const tabs=new Map();
 for(const shelf of SHELVES){
  const tab=el('a',undefined,'collection-tab'),glyph=el('img');glyph.src='/media/catalog/icons/'+shelf.icon+'.svg';glyph.alt='';glyph.width=28;glyph.height=28;
  tab.href='#'+shelf.id;tab.append(glyph,el('span',shelf.label),el('span',number.format(shelves.get(shelf.id).length),'collection-count'));
  tabs.set(shelf.id,tab);side.append(tab);
 }
 layout.append(side,panel);view.append(layout);
 if(data.source==='local-fixture'||data.updatedAt)view.append(el('p','Updated '+new Date(data.updatedAt||Date.now()).toLocaleString(),'pass-footnote'));

 function show(){
  const shelf=SHELVES.find(s=>s.id===location.hash.slice(1))||SHELVES[0],items=shelves.get(shelf.id);
  for(const[id,tab]of tabs){if(id===shelf.id)tab.setAttribute('aria-current','true');else tab.removeAttribute('aria-current');}
  const grid=el('div',undefined,'collection-grid');grid.append(...items.map(card));
  panel.replaceChildren(el('h2',shelf.title),el('p',shelf.text,'muted'),items.length?grid:el('div','Nothing in this collection yet.','empty'));
 }
 addEventListener('hashchange',show);show();
}
