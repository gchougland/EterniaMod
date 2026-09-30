import crypto from 'node:crypto';
import {route,requireUser,requireAdmin,HttpError} from './errors.js';
import {saveSession,UUID} from './auth.js';
import {checkoutView} from './tebex-headless.js';

export function mountTebexCheckout(app,cfg,headless,bridge){
 const packages=cfg.tebexPackages||[],busy=new Set();
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
  const offers=packages.map(p=>{const listing=live.find(l=>l.id===p.id&&l.type==='single'&&!l.requiresOptions);return listing?{...p,name:listing.name,price:listing.price,currency:listing.currency}:null;}).filter(p=>p&&Number.isFinite(p.price)&&p.price>=0&&/^[A-Z]{3}$/.test(p.currency));
  res.set('Cache-Control','no-store').json({offers,checkoutEnabled:enabled()&&(!cfg.tebexAdminOnly||Boolean(req.session.user?.admin)),configured:headless.configured});
 }));
 app.get('/api/admin/commerce',requireAdmin,route(async(req,res)=>{
  const live=await catalog();let gameOffers=[],bridgeReady=false;
  try{const result=await bridge.getCrownProducts();gameOffers=result.products||[];bridgeReady=result.source==='game';}catch{}
  res.set('Cache-Control','no-store').json({configured:headless.configured,checkoutEnabled:enabled(),adminOnly:Boolean(cfg.tebexAdminOnly),bridgeReady,
   signInReady:Boolean(cfg.clientId&&cfg.clientSecret),packages:live.map(p=>({...p,crowns:packages.find(m=>m.id===p.id)?.crowns||null,
    deliveryMapped:gameOffers.some(o=>o.id===p.id&&o.revision===packages.find(m=>m.id===p.id)?.revision&&o.crowns===packages.find(m=>m.id===p.id)?.crowns)}))});
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
 async function delivery(req,id){
  const mapping=packages.find(p=>p.id===id);if(!mapping)throw new HttpError(400,'Choose one of the available Crown packages.');
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
   if(!pending||pending.owner!==req.session.user.uuid||pending.packageId!==req.body.packageId||Date.now()-pending.createdAt>30*60000){
    if(Date.now()-(req.session.tebexAttempt||0)<5000)throw new HttpError(429,'Please wait a moment before choosing another package.');
    req.session.tebexAttempt=Date.now();await saveSession(req);
    const basket=await headless.create(req.session.user,req.ip.replace(/^::ffff:/,''));
    pending=req.session.tebexCheckout={ident:basket.ident,owner:req.session.user.uuid,packageId:req.body.packageId,createdAt:Date.now()};
    await saveSession(req);
   }
   res.set('Cache-Control','no-store').json(await ready(req,pending,await headless.get(pending.ident)));
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
