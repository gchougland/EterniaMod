import crypto from 'node:crypto';
import {route,requireUser,requireAdmin,HttpError} from './errors.js';
import {saveSession,UUID} from './auth.js';
import {checkoutView} from './tebex-headless.js';
import {saleWindow,closedReason} from '../web/js/season-sale.js';
import {RANKS,RANK_IDS,LADDER} from '../web/js/ranks.js';

/** `seasons` lists published passes ({id,name,startsOn,endsOn}). A package mapped to a season sells only inside its window. */
export function mountTebexCheckout(app,cfg,headless,bridge,seasons=async()=>[]){
 const packages=cfg.tebexPackages||[],ranks=cfg.tebexRanks||[],busy=new Set();
 const NO_RANKS={held:[],paid:[],ladder:'none'};
 const listingType=mapping=>mapping.rank==='eternal'?'subscription':'single';
 // The package a player should buy for a rank: the upgrade from the ladder rank they paid for when there is one, else
 // full price. Hand given ranks never count here: the game only accepts an upgrade on top of a paid step.
 // `live` is the Tebex catalog: an upgrade package Tebex does not list falls back to the full price package.
 function packageFor(rank,held,live){
  const listed=m=>m&&live.some(l=>l.id===m.id&&l.type===listingType(m)&&!l.requiresOptions);
  if(LADDER.includes(rank)){
   const current=held?.ladder||'none';
   if(current!=='none'&&LADDER.indexOf(rank)<=LADDER.indexOf(current))return {owned:true};
   const upgrade=ranks.find(r=>r.rank===rank&&r.from===current),full=ranks.find(r=>r.rank===rank&&r.from==='none');
   return {mapping:[upgrade,full].find(listed)||null};
  }
  // Founder and Eternal given by hand count as owned too: buying them again would add nothing.
  if(held?.paid?.includes(rank)||held?.held?.includes(rank))return {owned:true};
  const only=ranks.find(r=>r.rank===rank);
  return {mapping:listed(only)?only:null};
 }
 async function heldRanks(user){if(!user)return null;try{return (await bridge.getOverview(user.uuid))?.ranks||NO_RANKS;}catch{return null;}}
 // A package tied to a season the website does not know is closed, not open: fail safe.
 async function sale(mapping){
  if(!mapping.season)return null;
  const pass=(await seasons().catch(()=>[])).find(s=>s.id===mapping.season);
  return pass?{...saleWindow(pass),season:pass.name}:{state:'unknown',startsOn:null,endsOn:null,purchasable:false,season:''};
 }
 const enabled=()=>Boolean(cfg.tebexCheckoutEnabled&&headless.configured&&!cfg.fixtures&&cfg.clientId&&cfg.clientSecret&&cfg.bridgeUrl&&cfg.bridgeToken);
 let listing,expires=0,inflight;
 async function catalog(){
  if(!headless.configured)return [];
  if(listing&&Date.now()<expires)return listing;
  if(!inflight)inflight=headless.packages().then(data=>{listing=data;expires=Date.now()+30000;return data;}).finally(()=>{inflight=null;});
  return inflight;
 }
 app.get('/api/store',route(async(req,res)=>{
  const live=await catalog();
  const offers=(await Promise.all(packages.map(async p=>{const listing=live.find(l=>l.id===p.id&&l.type==='single'&&!l.requiresOptions);if(!listing)return null;
   const offer={...p,name:listing.name,price:listing.price,currency:listing.currency};const window=await sale(p);if(window)offer.sale=window;return offer;})))
   .filter(p=>p&&Number.isFinite(p.price)&&p.price>=0&&/^[A-Z]{3}$/.test(p.currency));
  res.set('Cache-Control','no-store').json({offers,checkoutEnabled:enabled()&&(!cfg.tebexAdminOnly||Boolean(req.session.user?.admin)),configured:headless.configured});
 }));
 app.get('/api/store/ranks',route(async(req,res)=>{
  const live=await catalog(),held=await heldRanks(req.session.user);
  const list=RANK_IDS.map(rank=>{
   const choice=packageFor(rank,held,live),base={rank,name:RANKS[rank].name,owned:Boolean(choice.owned),subscription:rank==='eternal'};
   if(choice.owned||!choice.mapping)return base;
   const listing=live.find(l=>l.id===choice.mapping.id&&l.type===listingType(choice.mapping)&&!l.requiresOptions);
   if(!listing||!Number.isFinite(listing.price)||listing.price<0||!/^[A-Z]{3}$/.test(listing.currency))return base;
   return {...base,packageId:choice.mapping.id,price:listing.price,currency:listing.currency,upgradeFrom:choice.mapping.from==='none'?null:choice.mapping.from};
  });
  res.set('Cache-Control','no-store').json({ranks:list,known:Boolean(held),checkoutEnabled:enabled()&&(!cfg.tebexAdminOnly||Boolean(req.session.user?.admin))});
 }));
 app.get('/api/admin/commerce',requireAdmin,route(async(req,res)=>{
  const live=await catalog();let gameOffers=[],bridgeReady=false;
  try{const result=await bridge.getCrownProducts();gameOffers=result.products||[];bridgeReady=result.source==='game';}catch{}
  let rankOffers=[];try{const result=await bridge.getRankProducts();rankOffers=result.source==='game'?result.products||[]:[];}catch{}
  const rankOf=id=>ranks.find(r=>r.id===id);
  res.set('Cache-Control','no-store').json({configured:headless.configured,checkoutEnabled:enabled(),adminOnly:Boolean(cfg.tebexAdminOnly),bridgeReady,
   signInReady:Boolean(cfg.clientId&&cfg.clientSecret),packages:live.map(p=>({...p,crowns:packages.find(m=>m.id===p.id)?.crowns||null,
    rank:rankOf(p.id)?{rank:rankOf(p.id).rank,from:rankOf(p.id).from}:null,
    deliveryMapped:rankOf(p.id)?rankOffers.some(o=>o.id===p.id&&o.revision===rankOf(p.id).revision&&o.rank===rankOf(p.id).rank&&o.from===rankOf(p.id).from)
     :gameOffers.some(o=>o.id===p.id&&o.revision===packages.find(m=>m.id===p.id)?.revision&&o.crowns===packages.find(m=>m.id===p.id)?.crowns)}))});
 }));
 async function guard(req){
  if(!enabled()||req.session.user.fixture)throw new HttpError(503,'Crown purchases are not open yet.');
  if(cfg.tebexAdminOnly&&!req.session.user.admin)throw new HttpError(403,'Crown purchases are not open yet.');
  if(!UUID.test(req.session.user.uuid)||!req.session.user.name)throw new HttpError(401,'Sign in with Hytale again.');
  if(busy.has(req.sessionID)||busy.size>=32)throw new HttpError(429,'Your checkout is being prepared. Please wait.');
 }
 async function ready(req,pending,basket){
  const user=req.session.user;
  if(basket.custom?.eternia_profile_uuid!==user.uuid)throw new HttpError(409,'This checkout belongs to another profile.');
  if(!basket.username){
   pending.authState??=crypto.randomBytes(24).toString('hex');
   const authUrl=await headless.auth(pending.ident,pending.authState);await saveSession(req);return {status:'authorize',authUrl};
  }
  if(basket.username.toLowerCase()!==user.name.toLowerCase())throw new HttpError(409,'Use the same Hytale profile for Eternia and Tebex.');
  if(basket.complete)throw new HttpError(409,'This checkout is already complete. Check your balance before making another purchase.');
  if(!Array.isArray(basket.packages))throw new HttpError(502,'Checkout could not be verified.');
  // An ambiguous provider timeout is resolved by inspecting this same basket on retry.
  if(basket.packages.length===0)basket=await headless.add(pending.ident,pending.packageId);
  const result=checkoutView(basket,pending,user);delete pending.authState;await saveSession(req);return result;
 }
 async function rankDelivery(req,mapping){
  const live=(await catalog()).find(p=>p.id===mapping.id&&p.type===listingType(mapping)&&!p.requiresOptions);if(!live)throw new HttpError(409,'This rank is unavailable right now.');
  const offers=await bridge.getRankProducts();
  if(offers.source!=='game'||!offers.products?.some(p=>p.id===mapping.id&&p.revision===mapping.revision&&p.rank===mapping.rank&&p.from===mapping.from))throw new HttpError(503,'Delivery for this rank is not ready yet.');
  const account=await bridge.getOverview(req.session.user.uuid);
  if(account.source!=='game'||account.account?.uuid?.toLowerCase()!==req.session.user.uuid)throw new HttpError(409,'Join Eternia once with this Hytale profile before buying a rank.');
  // Checked at every start and resume; the game checks again at delivery, so a stale page can never buy an upgrade without its base.
  const choice=packageFor(mapping.rank,account.ranks||NO_RANKS,await catalog());
  if(choice.owned)throw new HttpError(409,'You already have this rank.');
  if(choice.mapping?.id!==mapping.id)throw new HttpError(409,'Your price for this rank changed. Please choose it again.');
 }
 async function delivery(req,id){
  const rank=ranks.find(r=>r.id===id);if(rank)return rankDelivery(req,rank);
  const mapping=packages.find(p=>p.id===id);if(!mapping)throw new HttpError(400,'Choose one of the available Crown packages.');
  // Checked every time a checkout starts or resumes, never at delivery: a payment begun in time is always delivered.
  const window=await sale(mapping);if(window&&!window.purchasable)throw new HttpError(409,closedReason(window.season,window));
  const live=(await catalog()).find(p=>p.id===id&&p.type==='single'&&!p.requiresOptions);if(!live)throw new HttpError(409,'This Crown package is unavailable.');
  const offers=await bridge.getCrownProducts();
  if(offers.source!=='game'||!offers.products?.some(p=>p.id===id&&p.revision===mapping.revision&&p.crowns===mapping.crowns))throw new HttpError(503,'Delivery for this package is not ready yet.');
  const account=await bridge.getOverview(req.session.user.uuid);
  if(account.source!=='game'||account.account?.uuid?.toLowerCase()!==req.session.user.uuid)throw new HttpError(409,'Join Eternia once with this Hytale profile before buying Crowns.');
 }
 app.post('/api/store/checkout',requireUser,route(async(req,res)=>{
  await guard(req);
  if(!req.body||Object.keys(req.body).some(k=>k!=='packageId')||typeof req.body.packageId!=='string')throw new HttpError(400,'Choose one Crown package.');
  busy.add(req.sessionID);
  try{
   await delivery(req,req.body.packageId);
   let pending=req.session.tebexCheckout;
   let basket;
   const reusable=pending&&pending.owner===req.session.user.uuid&&pending.packageId===req.body.packageId&&Date.now()-pending.createdAt<=30*60000;
   if(reusable){
    // Keep uncertain attempts on the same basket. Only the provider can confirm it is finished.
    basket=await headless.get(pending.ident);
    if(basket.custom?.eternia_profile_uuid!==req.session.user.uuid)throw new HttpError(409,'This checkout belongs to another profile.');
    if(basket.username&&basket.username.toLowerCase()!==req.session.user.name.toLowerCase())throw new HttpError(409,'Use the same Hytale profile for Eternia and Tebex.');
   }
   if(!reusable||basket.complete===true){
    if(Date.now()-(req.session.tebexAttempt||0)<5000)throw new HttpError(429,'Please wait a moment before choosing another package.');
    req.session.tebexAttempt=Date.now();await saveSession(req);
    basket=await headless.create(req.session.user,req.ip.replace(/^::ffff:/,''));
    pending=req.session.tebexCheckout={ident:basket.ident,owner:req.session.user.uuid,packageId:req.body.packageId,createdAt:Date.now(),subscription:ranks.find(r=>r.id===req.body.packageId)?.rank==='eternal'};
    await saveSession(req);
    basket=await headless.get(pending.ident);
   }
   res.set('Cache-Control','no-store').json(await ready(req,pending,basket));
  }finally{busy.delete(req.sessionID);}
 }));
 app.post('/api/store/checkout/resume',requireUser,route(async(req,res)=>{
  await guard(req);const pending=req.session.tebexCheckout;
  if(!pending||pending.owner!==req.session.user.uuid||Date.now()-pending.createdAt>30*60000||!pending.authState||req.body?.state!==pending.authState)
   throw new HttpError(400,'This checkout has expired. Please choose your package again.');
  busy.add(req.sessionID);
  try{await delivery(req,pending.packageId);res.set('Cache-Control','no-store').json(await ready(req,pending,await headless.get(pending.ident)));}
  finally{busy.delete(req.sessionID);}
 }));
 app.post('/api/store/checkout/reset',requireUser,route(async(req,res)=>{
  if(busy.has(req.sessionID))throw new HttpError(429,'Your checkout is being prepared. Please wait.');
  delete req.session.tebexCheckout;await saveSession(req);res.json({ok:true});
 }));
}
