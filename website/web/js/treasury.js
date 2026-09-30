import {api,el,link,notice} from './api.js';
const money=(amount,currency)=>new Intl.NumberFormat(undefined,{style:'currency',currency}).format(amount);
let sdk;
function loadCheckout(){
 if(window.Tebex?.checkout)return Promise.resolve(window.Tebex);
 if(sdk)return sdk;
 sdk=new Promise((resolve,reject)=>{const script=document.createElement('script');script.src='https://js.tebex.io/v/1.js';script.async=true;
  const timer=setTimeout(()=>{script.remove();sdk=null;reject(new Error('Checkout did not load. Please try again.'));},15000);
  script.onload=()=>{clearTimeout(timer);if(window.Tebex?.checkout)resolve(window.Tebex);else{sdk=null;reject(new Error('Checkout is unavailable.'));}};
  script.onerror=()=>{clearTimeout(timer);script.remove();sdk=null;reject(new Error('Checkout could not load. Please check your connection.'));};document.head.append(script);});return sdk;
}
export async function treasury(view,state){
 const intro=el('section',undefined,'treasury-intro');intro.append(el('div','The Crown treasury','eyebrow'),el('h1','A little more magic.'),el('p','Choose your Crowns, then find something you love in the Crown Store. Furnish your home, meet a companion, or create a new look.','muted'));view.append(intro);
 const balance=el('div',undefined,'treasury-account');view.append(balance);
 async function refreshBalance(){if(!state.user)return;try{const account=await api('/api/account');balance.replaceChildren(el('span',state.user.name,'pill'),el('strong',new Intl.NumberFormat().format(account.crowns.available)+' Crowns'),el('span',account.crowns.owed?'Crowns owed after a refund: '+account.crowns.owed:'Your current in game balance','muted'));}catch{balance.replaceChildren(el('span','Your balance is temporarily unavailable.','muted'));}}
 await refreshBalance();
 const panel=el('section',undefined,'panel checkout-review');panel.hidden=true;panel.setAttribute('aria-live','polite');view.append(panel);
 const grid=el('div',undefined,'crown-grid');view.append(grid);let loading=false,eventsBound=false;
 async function showCheckout(result){
  panel.hidden=false;panel.replaceChildren();
  if(result.status==='authorize'){panel.append(el('h2','Confirm your Hytale profile'),el('p','Tebex needs to confirm your profile before payment. Choose the same profile you used to sign in here.'),link('Continue with Hytale',result.authUrl,'button primary'));return;}
  const pack=data.offers.find(p=>p.id===result.packageId);
  panel.append(el('div','Review your purchase','eyebrow'),el('h2',pack?.name||'Your Crowns'),el('p',(pack?new Intl.NumberFormat().format(pack.crowns)+' Crowns for ':'Checkout for ')+state.user.name),el('div',money(result.price,result.currency),'number'),el('p','Tebex handles payment. Any final taxes and payment details are shown at checkout.','muted'));
  const pay=el('button','Loading secure checkout…','primary');pay.disabled=true;panel.append(pay);
  const fallback=link('Open secure checkout in a new tab',result.checkoutUrl);fallback.target='_blank';fallback.rel='noopener noreferrer';panel.append(fallback);
  const again=el('button','Choose another package');again.onclick=async()=>{try{await api('/api/store/checkout/reset',{method:'POST',body:'{}'});panel.hidden=true;}catch(e){notice(e.message,true);}};panel.append(again);
  try{const Tebex=await loadCheckout();Tebex.checkout.init({ident:result.ident,theme:'dark',colors:[{name:'primary',color:'#d8b66b'},{name:'secondary',color:'#6c9a87'}]});
   if(!eventsBound){
    Tebex.checkout.on('payment:complete',()=>{notice('Checkout finished. Your Crowns will appear after delivery is confirmed.');refreshBalance();});
    Tebex.checkout.on('payment:error',()=>notice('Payment could not be completed. You can try again in checkout.',true));eventsBound=true;
   }
   pay.textContent='Continue to secure payment';pay.disabled=false;pay.onclick=()=>{try{Tebex.checkout.launch();}catch{notice('Use the secure checkout link to continue.',true);}};
  }catch(e){pay.textContent='Checkout could not load';notice(e.message+' You can use the secure checkout link instead.',true);}
  panel.scrollIntoView({block:'nearest',behavior:'auto'});
 }
 let data;
 try{data=await api('/api/store');}catch(e){grid.append(el('div',e.message,'empty'));return;}
 if(!data.checkoutEnabled)view.insertBefore(el('p','The treasury is being prepared. Purchases will open soon.','notice'),grid);
 for(const [index,pack] of data.offers.entries()){
  const card=el('article',undefined,'card crown-package tier-'+index),icon=el('img');icon.src='/icons/crown.svg';icon.alt='';icon.width=100;icon.height=100;
  card.append(icon,el('h2',pack.name),el('div',new Intl.NumberFormat().format(pack.crowns),'number'),el('div','Crowns','eyebrow'),el('p','Spend on your next favorite in Eternia.'),el('strong',money(pack.price,pack.currency),'crown-price'));
  if(!state.user&&state.authConfigured)card.append(link('Sign in to purchase','/auth/login?returnTo=store','button primary'));
  else{const buy=el('button','Choose package','primary');buy.disabled=!data.checkoutEnabled||!state.user;
   buy.onclick=async()=>{if(loading)return;loading=true;buy.disabled=true;buy.textContent='Preparing checkout…';try{await showCheckout(await api('/api/store/checkout',{method:'POST',body:JSON.stringify({packageId:pack.id})}));}catch(e){notice(e.message,true);}finally{loading=false;buy.disabled=false;buy.textContent='Choose package';}};card.append(buy);}
  grid.append(card);
 }
 if(!data.offers.length)grid.append(el('div','Crown packages will appear here once the treasury is connected.','empty'));
 view.append(el('p','Crowns do not expire and cannot be traded. They are separate from the Coins earned through play.','muted'));
 view.append(el('p','Secure payments powered by Tebex.','muted'));
 if(state.user){
  const actions=el('div',undefined,'actions'),refresh=el('button','Refresh balance'),restart=el('button','Start a new checkout');
  refresh.onclick=refreshBalance;
  restart.onclick=async()=>{try{await api('/api/store/checkout/reset',{method:'POST',body:'{}'});panel.hidden=true;notice('Choose a package to start a new checkout.');history.replaceState(null,'','/store');}catch(e){notice(e.message,true);}};
  actions.append(refresh,restart);view.append(actions);
 }
 if(state.user?.admin)view.append(link('Manage treasury','/admin/commerce'));
 const query=new URLSearchParams(location.search);
 if(query.get('checkout')==='authorize'&&query.get('state')){const stateToken=query.get('state');history.replaceState(null,'','/store');try{await showCheckout(await api('/api/store/checkout/resume',{method:'POST',body:JSON.stringify({state:stateToken})}));}catch(e){notice(e.message,true);}}
 else if(query.get('checkout')==='returned')notice('Check your balance here after Tebex confirms payment and Eternia completes delivery.');
 else if(query.get('checkout')==='cancelled')notice('Checkout closed. You can choose a package whenever you are ready.');
}
