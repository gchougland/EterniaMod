import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import session from 'express-session';
import {createHeadless,checkoutView,safeTebexUrl} from '../server/tebex-headless.js';
import {mountTebexCheckout} from '../server/tebex-checkout.js';
import {config} from '../server/config.js';
import {errorHandler} from '../server/errors.js';

const user={uuid:'11111111-1111-4111-8111-111111111111',name:'Explorer',admin:true};
const mapping={id:'7704919',revision:1,crowns:500,name:'Pouch of Crowns'};
const listing={id:mapping.id,name:mapping.name,type:'single',price:5,currency:'USD'};
const pending={packageId:mapping.id,ident:'basket-test-123'};
function basket(){return {ident:pending.ident,username:user.name,username_id:123,custom:{eternia_profile_uuid:user.uuid},complete:false,packages:[{id:Number(mapping.id),type:'single',is_recurring:false,in_basket:{quantity:1}}],total_price:5.25,currency:'USD',links:{checkout:'https://pay.tebex.io/'+pending.ident}};}

test('Headless uses documented paths, server identity, buyer address, and bounded private credentials',async()=>{
 const calls=[];const cfg={baseUrl:'https://eternia-hytale.com',tebexPublicToken:'public-token',tebexPrivateKey:'private-test-key'};
 const client=createHeadless(cfg,async(url,options)=>{calls.push({url,options});return Response.json({data:url.endsWith('/packages')&&options.method==='GET'?[{...listing,id:Number(mapping.id),total_price:5,options:[],variables:[]}]:basket()});});
 assert.equal((await client.packages())[0].price,5);
 await client.create(user,'203.0.113.12');const creation=calls[1];
 assert.equal(creation.url,'https://headless.tebex.io/api/accounts/public-token/baskets');
 assert.equal(creation.options.redirect,'error');assert.ok(creation.options.signal);
 assert.equal(creation.options.headers.Authorization,'Basic '+Buffer.from('public-token:private-test-key').toString('base64'));
 const body=JSON.parse(creation.options.body);assert.equal(body.username,user.name);assert.equal(body.ip_address,'203.0.113.12');assert.equal(body.custom.eternia_profile_uuid,user.uuid);
 assert.equal(body.complete_url,'https://eternia-hytale.com/store?checkout=returned');
 await client.add(pending.ident,mapping.id);assert.equal(calls[2].url,'https://headless.tebex.io/api/baskets/basket-test-123/packages');
 assert.deepEqual(JSON.parse(calls[2].options.body),{package_id:mapping.id,quantity:1});
 await assert.rejects(client.create(user,'not-an-ip'),/address/);await assert.rejects(client.get('../unsafe'),/prepared/);
 const failing=createHeadless(cfg,async()=>{throw new Error('private-test-key');});await assert.rejects(failing.packages(),e=>!e.message.includes('private-test-key')&&e.status===503);
 const oversized=createHeadless(cfg,async()=>new Response(' '.repeat(1024*1024+1)));await assert.rejects(oversized.packages(),e=>e.status===503);
});

test('checkout rejects changed profiles, gifts, quantities, packages, completed baskets and unsafe links',t=>{
 t.mock.method(console,'warn',()=>{});
 assert.equal(checkoutView(basket(),pending,user).price,5.25);
 for(const change of [b=>b.username='SomeoneElse',b=>b.custom.eternia_profile_uuid='different',b=>b.complete=true,b=>b.packages.push(b.packages[0]),b=>b.packages[0].id=999,b=>b.packages[0].in_basket.quantity=2,b=>b.packages[0].in_basket.gift_username_id='other',b=>b.packages[0].is_recurring=true,b=>b.packages[0].type='subscription',b=>b.total_price=NaN,b=>b.currency='invalid',b=>b.links.checkout='https://tebex.io.evil.example']){
  const value=basket();change(value);assert.throws(()=>checkoutView(value,pending,user));
 }
 for(const url of ['http://pay.tebex.io','https://pay.tebex.io@evil.example','https://user:secret@pay.tebex.io','javascript:alert(1)'])assert.equal(safeTebexUrl(url),null);
});

test('basket conflicts identify each rejected field without logging buyer or basket data',t=>{
 const warnings=t.mock.method(console,'warn',()=>{});
 const cases=[
  [b=>b.packages=[],['package_count']],
  [b=>b.packages[0].id=999,['package_id']],
  [b=>b.packages[0].in_basket.quantity=2,['quantity']],
  [b=>b.packages[0].in_basket.gift_username_id='private-recipient',['gift']],
  [b=>b.packages[0].is_recurring='false',['recurring']],
  [b=>delete b.packages[0].type,['purchase_type']],
  [b=>b.packages=[null],['package_id','quantity','purchase_type']],
 ];
 for(const [change,reasons] of cases){
  const value=basket();change(value);value.email='private@example.com';
  assert.throws(()=>checkoutView(value,pending,user),e=>e.status===409);
  const [message,json]=warnings.mock.calls.at(-1).arguments;
  assert.equal(message,'Tebex basket validation failed');assert.deepEqual(JSON.parse(json).reasons,reasons);
  for(const secret of ['private-recipient','private@example.com',pending.ident,user.uuid,user.name])assert.ok(!json.includes(secret));
 }
 const value=basket();value.packages[0].type='private provider value';value.packages[0].in_basket.quantity={private:'data'};
 assert.throws(()=>checkoutView(value,pending,user));
 const logged=JSON.parse(warnings.mock.calls.at(-1).arguments[1]);
 assert.deepEqual(logged.purchaseType,{type:'string'});assert.deepEqual(logged.quantity,{type:'object'});
 const count=warnings.mock.calls.length;assert.equal(checkoutView(basket(),pending,user).packageId,mapping.id);assert.equal(warnings.mock.calls.length,count);
});

test('Hytale basket authorization accepts only a provider HTTPS URL and encodes the return state',async()=>{
 let requested;const client=createHeadless({tebexPublicToken:'public-token',baseUrl:'https://eternia-hytale.com'},async url=>{requested=url;return Response.json([{name:'Hytale',url:'https://headless.tebex.io/authorize/123'}]);});
 assert.equal(await client.auth(pending.ident,'test-state'),'https://headless.tebex.io/authorize/123');
 assert.equal(new URL(requested).searchParams.get('returnUrl'),'https://eternia-hytale.com/store?checkout=authorize&state=test-state');
 const bad=createHeadless({tebexPublicToken:'public-token'},async()=>Response.json([{name:'Hytale',url:'https://evil.example'}]));await assert.rejects(bad.auth(pending.ident,'state'),/verify/);
});

async function harness(t,changes={}){
 const state={user:{...user},current:basket(),creates:0,adds:0,products:[mapping],source:'game',accountUuid:user.uuid,failAdd:false};
 const cfg={tebexPackages:[mapping],tebexCheckoutEnabled:true,clientId:'configured',clientSecret:'configured',bridgeUrl:'https://game.example',bridgeToken:'configured',...changes};
 const headless={configured:true,packages:async()=>[listing],create:async()=>{state.creates++;state.current=basket();state.current.packages=[];return state.current;},get:async()=>structuredClone(state.current),auth:async(id,nonce)=>'https://headless.tebex.io/auth?state='+nonce,
  add:async()=>{state.adds++;state.current.packages=basket().packages;if(state.failAdd){state.failAdd=false;throw Object.assign(new Error('Timeout'),{status:503});}return structuredClone(state.current);}};
 const bridge={getCrownProducts:async()=>({source:state.source,products:state.products}),getOverview:async()=>({source:state.source,account:{uuid:state.accountUuid}})};
 const app=express();app.use(session({secret:'local-test-secret-12345678901234567890',resave:false,saveUninitialized:false}));app.use(express.json());
 app.use((req,res,next)=>{req.session.user=state.user;req.session.csrf='test-token';next();});
 app.use((req,res,next)=>req.method==='POST'&&req.get('X-CSRF-Token')!=='test-token'?res.sendStatus(403):next());
 mountTebexCheckout(app,cfg,headless,bridge);app.use(errorHandler);
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));let cookie='';
 t.after(async()=>{server.closeAllConnections();await new Promise(r=>server.close(r));});
 const request=async(path,body,headers={})=>{const response=await fetch('http://127.0.0.1:'+server.address().port+path,{method:body===undefined?'GET':'POST',headers:{Cookie:cookie,'Content-Type':'application/json','X-CSRF-Token':'test-token',...headers},body:body===undefined?undefined:JSON.stringify(body)});if(response.headers.has('set-cookie'))cookie=response.headers.get('set-cookie').split(';')[0];return response;};
 return {state,request,headless};
}
test('checkout reuses a basket after ambiguous add timeout and does not double add or accept client amounts',async t=>{
 const {state,request}=await harness(t);state.failAdd=true;
 assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,503);
 const response=await request('/api/store/checkout',{packageId:mapping.id});assert.equal(response.status,200);assert.equal((await response.json()).ident,pending.ident);
 assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,200);assert.equal(state.creates,1);assert.equal(state.adds,1);
 assert.equal((await request('/api/store/checkout',{packageId:mapping.id,price:0,uuid:'other'})).status,400);
 assert.equal((await request('/api/store/checkout',{packageId:'unknown'})).status,400);
 assert.equal((await request('/api/store/checkout',{packageId:mapping.id},{'X-CSRF-Token':''})).status,403);
 assert.equal((await request('/api/store/checkout/reset',{})).status,200);
 assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,429);
});
test('checkout gates on real login, game account and exact configured Crown benefit',async t=>{
 const {state,request}=await harness(t);
 state.user=null;assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,401);assert.equal((await request('/api/admin/commerce')).status,403);
 state.user={...user,fixture:true};assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,503);
 state.user={...user,admin:false};assert.equal((await request('/api/admin/commerce')).status,403);
 state.products=[{...mapping,crowns:1}];assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,503);
 state.products=[mapping];state.source='local-fixture';assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,503);
 state.source='game';state.accountUuid='other';assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,409);assert.equal(state.creates,0);
});
test('authorization resume needs its session state and the same profile',async t=>{
 const {state,request,headless}=await harness(t);const original=headless.create;headless.create=async()=>{const b=await original();b.username=null;return b;};
 const auth=await (await request('/api/store/checkout',{packageId:mapping.id})).json();assert.equal(auth.status,'authorize');
 const nonce=new URL(auth.authUrl).searchParams.get('state');assert.equal((await request('/api/store/checkout/resume',{state:'wrong'})).status,400);
 state.current.username='Other';assert.equal((await request('/api/store/checkout/resume',{state:nonce})).status,409);
 state.current.username=user.name;assert.equal((await request('/api/store/checkout/resume',{state:nonce})).status,200);
 assert.equal((await request('/api/store/checkout/resume',{state:nonce})).status,400);assert.equal(state.adds,1);
});
test('catalog uses provider prices, admin shows IDs, default and local modes cannot purchase',async t=>{
 const {state,request}=await harness(t,{fixtures:true});
 const catalog=await (await request('/api/store')).json();assert.equal(catalog.checkoutEnabled,false);assert.equal(catalog.offers[0].price,5);assert.equal(catalog.offers[0].crowns,500);
 const admin=await (await request('/api/admin/commerce')).json();assert.equal(admin.packages[0].id,mapping.id);assert.equal(admin.packages[0].deliveryMapped,true);
 assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,503);assert.equal(state.creates,0);
 const cfg=config({LOCAL_FIXTURES:'true'});assert.equal(cfg.tebexCheckoutEnabled,false);assert.deepEqual(cfg.tebexPackages.map(p=>p.crowns),[500,1100,2300,6000]);
 assert.equal(cfg.tebexAdminOnly,true);
});
test('provider testing can be restricted to current administrators',async t=>{
 const {state,request}=await harness(t,{tebexAdminOnly:true});state.user={...user,admin:false};
 assert.equal((await (await request('/api/store')).json()).checkoutEnabled,false);
 assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,403);
 state.user={...user};assert.equal((await request('/api/store/checkout',{packageId:mapping.id})).status,200);
});

test('checkout preserves safe provider errors and logs only the failing operation and HTTP status',async t=>{
 const warnings=t.mock.method(console,'warn',()=>{});
 const {request,headless}=await harness(t);
 const client=createHeadless({tebexPublicToken:'private-catalog-token',tebexPrivateKey:'private-api-key',baseUrl:'https://eternia.example'},async()=>new Response('sensitive provider response',{status:403}));
 headless.create=()=>client.create(user,'203.0.113.12');
 const response=await request('/api/store/checkout',{packageId:mapping.id});
 assert.equal(response.status,503);
 assert.deepEqual(await response.json(),{error:'Tebex could not prepare your purchase. Please try again later.'});
 assert.deepEqual(warnings.mock.calls.map(call=>call.arguments),[['Tebex request failed',JSON.stringify({operation:'create_basket',status:403,failure:'http',authConfigured:true})]]);
});

test('unexpected checkout exceptions remain private through the production error handler',async t=>{
 const {request,headless}=await harness(t);
 headless.create=async()=>{throw new Error('private-api-key and buyer data');};
 const response=await request('/api/store/checkout',{packageId:mapping.id});
 assert.equal(response.status,500);
 assert.deepEqual(await response.json(),{error:'Service unavailable. Please try again.'});
});

test('provider connection and response failures log no raw data',async t=>{
 const warnings=t.mock.method(console,'warn',()=>{});
 for(const [fetcher,status,failure] of [
  [async()=>{throw new Error('private transport details');},null,'connection'],
  [async()=>new Response('private malformed response'),200,'response'],
 ]){
  const client=createHeadless({tebexPublicToken:'private-catalog-token'},fetcher);
  await assert.rejects(client.get('private-basket-id'),e=>e.status===503&&!e.message.includes('private'));
  assert.deepEqual(warnings.mock.calls.at(-1).arguments,['Tebex request failed',JSON.stringify({operation:'read_basket',status,failure,authConfigured:false})]);
 }
});

test('422 diagnostics distinguish missing authentication from provider rejection without exposing request values',async t=>{
 const warnings=t.mock.method(console,'warn',()=>{});
 const cfg={tebexPublicToken:'catalog-token',tebexPrivateKey:'secret/key+value',baseUrl:'https://eternia.example'};
 const basic=Buffer.from(cfg.tebexPublicToken+':'+cfg.tebexPrivateKey).toString('base64');
 const client=createHeadless(cfg,async(url,options)=>{
  assert.equal(options.headers.Authorization,'Basic '+basic);
  return Response.json({title:'Request payload error',detail:`Invalid username: ${user.name}. ${user.uuid} 203.0.113.12 ${cfg.tebexPublicToken} ${cfg.tebexPrivateKey} ${encodeURIComponent(cfg.tebexPrivateKey)} Basic ${basic}\nhttps://private.example/secret other@example.com`,error_code:'validation_failed',meta:{private:'do not log metadata'}},{status:422});
 });
 await assert.rejects(client.create(user,'203.0.113.12'),e=>e.status===503&&!e.message.includes('Invalid username'));
 const logged=JSON.parse(warnings.mock.calls.at(-1).arguments[1]);
 assert.equal(logged.authConfigured,true);assert.equal(logged.status,422);assert.equal(logged.failure,'http');
 assert.equal(logged.title,'Request payload error');assert.equal(logged.code,'validation_failed');
 assert.match(logged.detail,/^Invalid username: \[redacted\]/);
 for(const secret of [user.name,user.uuid,'203.0.113.12',cfg.tebexPublicToken,cfg.tebexPrivateKey,encodeURIComponent(cfg.tebexPrivateKey),basic,'private.example','other@example.com','do not log metadata','\n'])assert.ok(!JSON.stringify(logged).includes(secret),secret);
 const missing=createHeadless({tebexPublicToken:cfg.tebexPublicToken},async()=>Response.json({detail:'Basic auth credentials are required'},{status:422}));
 await assert.rejects(missing.get(pending.ident));
 assert.deepEqual(JSON.parse(warnings.mock.calls.at(-1).arguments[1]),{operation:'read_basket',status:422,failure:'http',authConfigured:false,detail:'Basic auth credentials are required'});
});

test('provider diagnostics bound error bodies and text while hiding non JSON responses',async t=>{
 const warnings=t.mock.method(console,'warn',()=>{});
 for(const response of [new Response('<html>private response</html>',{status:422}),new Response('x'.repeat(8193),{status:422})]){
  const client=createHeadless({tebexPublicToken:'catalog-token'},async()=>response);
  await assert.rejects(client.get(pending.ident));
  assert.deepEqual(JSON.parse(warnings.mock.calls.at(-1).arguments[1]),{operation:'read_basket',status:422,failure:'http',authConfigured:false});
 }
 const client=createHeadless({tebexPublicToken:'catalog-token'},async()=>Response.json({detail:'x'.repeat(1000)+' '+pending.ident},{status:422}));
 await assert.rejects(client.get(pending.ident));
 assert.equal(JSON.parse(warnings.mock.calls.at(-1).arguments[1]).detail.length,500);
});
