import {api,el,identityBar,notice} from '/js/api.js';
await identityBar();
try{const data=await api('/api/admin/commerce'),status=document.querySelector('#status');
 status.append(el('h2','Connection status'));
 status.append(el('p',data.adminOnly?'Checkout access: Administrators only':'Checkout access: All signed in players'));
 for(const [label,ready] of [['Tebex catalog',data.configured],['Hytale sign in',data.signInReady],['Game server connection',data.bridgeReady],['Website checkout',data.checkoutEnabled]])status.append(el('p',label+': '+(ready?'Ready':'Not ready')));
 const grid=document.querySelector('#packages');
 for(const pack of data.packages){const card=el('article',undefined,'card');card.append(el('h3',pack.name),el('p','Package ID: '+pack.id),el('p',pack.crowns?pack.crowns.toLocaleString()+' Crowns':'Not mapped to Crowns'),el('p',pack.price+' '+pack.currency),el('p',pack.deliveryMapped?'Crown delivery configured':'Crown delivery needs configuration'));const copy=el('button','Copy package ID');copy.onclick=async()=>{try{await navigator.clipboard.writeText(pack.id);notice('Package ID copied.');}catch{notice('Select and copy the package ID above.');}};card.append(copy);grid.append(card);}
 if(!data.configured)status.append(el('p','Set TEBEX_PUBLIC_TOKEN in the Railway website service, then redeploy.'));
 status.append(el('p','Connection status does not confirm payment delivery. Complete a provider test purchase before opening the treasury.','muted'));
}catch(e){notice(e.message,true);}
