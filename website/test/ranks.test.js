import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import session from 'express-session';
import {mountTebexCheckout} from '../server/tebex-checkout.js';
import {checkoutView} from '../server/tebex-headless.js';
import {validRankPackages} from '../server/config.js';
import {errorHandler} from '../server/errors.js';

const user={uuid:'11111111-1111-4111-8111-111111111111',name:'Explorer',admin:true};
const ranks=[
 {id:'9001',revision:1,rank:'wanderer',from:'none'},{id:'9002',revision:1,rank:'starborn',from:'none'},
 {id:'9003',revision:1,rank:'celestial',from:'none'},{id:'9012',revision:1,rank:'starborn',from:'wanderer'},
 {id:'9023',revision:1,rank:'celestial',from:'starborn'},{id:'9100',revision:1,rank:'founder',from:'none'},
 {id:'9200',revision:1,rank:'eternal',from:'none'}];
const prices={'9001':9.99,'9002':14.99,'9003':24.99,'9012':5,'9023':10,'9100':24.99,'9200':9.99};
const listings=ranks.map(r=>({id:r.id,name:r.rank,type:r.rank==='eternal'?'subscription':'single',price:prices[r.id],currency:'USD'}));

async function harness(t){
 const state={held:{held:[],paid:[],ladder:'none'},products:ranks.map(r=>({...r,subscription:r.rank==='eternal'})),added:[],recurring:false};
 const cfg={tebexPackages:[],tebexRanks:ranks,tebexCheckoutEnabled:true,clientId:'c',clientSecret:'s',bridgeUrl:'https://game.example',bridgeToken:'t'};
 const basket=()=>({ident:'basket-rank-1',username:user.name,custom:{eternia_profile_uuid:user.uuid},complete:false,
  packages:state.added.map(id=>({id:Number(id),type:state.recurring?'subscription':'single',is_recurring:state.recurring,in_basket:{quantity:1}})),total_price:1,currency:'USD',links:{checkout:'https://pay.tebex.io/basket-rank-1'}});
 const headless={configured:true,packages:async()=>listings,create:async()=>{state.added=[];return basket();},get:async()=>basket(),auth:async()=>'https://headless.tebex.io/auth',
  add:async(ident,id)=>{state.added=[id];state.recurring=id==='9200';return basket();}};
 const bridge={getCrownProducts:async()=>({source:'game',products:[]}),getRankProducts:async()=>({source:'game',products:state.products}),
  getOverview:async()=>({source:'game',account:{uuid:user.uuid},ranks:state.held})};
 const app=express();app.use(session({secret:'local-test-secret-12345678901234567890',resave:false,saveUninitialized:false}));app.use(express.json());
 app.use((req,res,next)=>{req.session.user={...user};req.session.csrf='x';next();});
 mountTebexCheckout(app,cfg,headless,bridge);app.use(errorHandler);
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));
 t.after(async()=>{server.closeAllConnections();await new Promise(r=>server.close(r));});
 let cookie='';
 const request=async(path,body)=>{const res=await fetch('http://127.0.0.1:'+server.address().port+path,{method:body?'POST':'GET',headers:{Cookie:cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
  if(res.headers.has('set-cookie'))cookie=res.headers.get('set-cookie').split(';')[0];return res;};
 return {state,request};
}
const byRank=list=>Object.fromEntries(list.ranks.map(r=>[r.rank,r]));

test('rank package configuration is checked',()=>{
 assert.equal(validRankPackages(ranks),true);
 assert.equal(validRankPackages([{id:'1',revision:1,rank:'starborn',from:'celestial'}]),false,'an upgrade goes up');
 assert.equal(validRankPackages([{id:'1',revision:1,rank:'founder',from:'wanderer'}]),false,'Founder is not on the ladder');
 assert.equal(validRankPackages([{id:'1',revision:1,rank:'wanderer',from:'none'},{id:'2',revision:1,rank:'wanderer',from:'none'}]),false,'one package per step');
 assert.equal(validRankPackages([{id:'1',revision:1,rank:'admin',from:'none'}]),false);
});

test('a new player sees full prices; a Starborn sees the upgrade price and what they own',async t=>{
 const {state,request}=await harness(t);
 let list=byRank(await (await request('/api/store/ranks')).json());
 assert.equal(list.celestial.price,24.99);assert.equal(list.celestial.upgradeFrom,null);assert.equal(list.eternal.subscription,true);
 assert.equal(list.cosmic.packageId,undefined,'no package set up yet means coming soon');
 state.held={held:['wanderer','starborn'],paid:['wanderer','starborn'],ladder:'starborn'};
 list=byRank(await (await request('/api/store/ranks')).json());
 assert.equal(list.wanderer.owned,true);assert.equal(list.starborn.owned,true);
 assert.equal(list.celestial.packageId,'9023');assert.equal(list.celestial.price,10);assert.equal(list.celestial.upgradeFrom,'starborn');
});

test('hand given ranks do not unlock upgrade prices',async t=>{
 const {state,request}=await harness(t);
 state.held={held:['starborn'],paid:[],ladder:'none'};
 const list=byRank(await (await request('/api/store/ranks')).json());
 assert.equal(list.celestial.packageId,'9003','full price: the game only accepts an upgrade on top of a paid rank');
});

test('checkout refuses owned ranks and upgrade packages that no longer match',async t=>{
 const {state,request}=await harness(t);
 assert.equal((await request('/api/store/checkout',{packageId:'9023'})).status,409,'an upgrade from Starborn without Starborn');
 const ok=await request('/api/store/checkout',{packageId:'9003'});assert.equal(ok.status,200,await ok.clone().text());
 state.held={held:['wanderer','starborn','celestial'],paid:['wanderer','starborn','celestial'],ladder:'celestial'};
 assert.equal((await request('/api/store/checkout/reset',{})).status,200);
 const owned=await request('/api/store/checkout',{packageId:'9002'});assert.equal(owned.status,409);assert.match((await owned.json()).error,/already have/);
 state.products=[];
 await request('/api/store/checkout/reset',{});
 assert.equal((await request('/api/store/checkout',{packageId:'9100'})).status,503,'the game must know the package');
});

test('Eternal goes through as a subscription and only a recurring basket passes',async t=>{
 const {state,request}=await harness(t);
 const res=await request('/api/store/checkout',{packageId:'9200'});
 assert.equal(res.status,200,await res.clone().text());assert.equal((await res.json()).status,'ready');
 const pending={packageId:'9200',subscription:true};
 const one={ident:'basket-rank-1',username:user.name,custom:{eternia_profile_uuid:user.uuid},complete:false,packages:[{id:9200,type:'single',is_recurring:false,in_basket:{quantity:1}}],total_price:9.99,currency:'USD',links:{checkout:'https://pay.tebex.io/x'}};
 assert.throws(()=>checkoutView(one,pending,user),/changed/);
 assert.throws(()=>checkoutView({...one,packages:[{id:9200,type:'subscription',is_recurring:true,in_basket:{quantity:1}}]},{packageId:'9200'},user),/changed/,'a one time checkout never takes a subscription');
});
