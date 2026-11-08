import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import session from 'express-session';
import {mountTebexCheckout} from '../server/tebex-checkout.js';
import {errorHandler} from '../server/errors.js';
import {saleWindow,saleBadge,premiumNote,closedReason,shortDay,longDay} from '../web/js/season-sale.js';

const winter={id:'eternia:winter',name:'Winter',startsOn:'2026-12-01',endsOn:'2027-01-30'};
const at=iso=>Date.parse(iso);

test('a season sale window uses whole UTC days with an inclusive last day',()=>{
 assert.equal(saleWindow(winter,at('2026-11-30T23:59:59.999Z')).state,'upcoming');
 assert.equal(saleWindow(winter,at('2026-12-01T00:00:00Z')).state,'on_sale');
 assert.equal(saleWindow(winter,at('2027-01-30T23:59:59.999Z')).state,'on_sale');
 assert.equal(saleWindow(winter,at('2027-01-31T00:00:00Z')).state,'ended');
 // Late evening on the last day in Los Angeles is already the next day in UTC.
 assert.equal(saleWindow(winter,at('2027-01-30T18:00:00-08:00')).state,'ended');
 assert.equal(saleWindow({name:'Open'},0).state,'unscheduled');assert.equal(saleWindow({name:'Open'},0).purchasable,true);
 assert.equal(saleWindow({startsOn:'2026-12-01'},at('2099-01-01T00:00:00Z')).state,'on_sale');
 assert.equal(saleWindow({startsOn:'not a date',endsOn:'2026-13-45'},0).state,'unscheduled');
 assert.equal(saleWindow(winter,at('2026-12-10T00:00:00Z')).purchasable,true);
 assert.equal(saleWindow(winter,at('2027-02-10T00:00:00Z')).purchasable,false);
});

test('badges and notes read plainly for each state',()=>{
 const now=at('2026-11-07T00:00:00Z');
 assert.deepEqual(saleBadge(saleWindow(winter,now),now),{text:'Coming soon',tone:'upcoming',title:'Opens 1 December 2026'});
 assert.equal(saleBadge(saleWindow(winter,at('2027-01-02T00:00:00Z')),at('2027-01-02T00:00:00Z')).text,'Available until 30 Jan');
 assert.equal(saleBadge(saleWindow(winter,at('2026-12-02T00:00:00Z')),at('2026-12-02T00:00:00Z')).text,'Available until 30 Jan 2027');
 assert.equal(saleBadge(saleWindow(winter,at('2027-03-01T00:00:00Z'))).text,'Season ended');
 assert.equal(saleBadge(saleWindow({},now)),null);
 assert.equal(shortDay('2027-01-30',at('2027-05-01T00:00:00Z')),'30 Jan');assert.equal(longDay('2026-12-01'),'1 December 2026');
 assert.match(premiumNote(winter,saleWindow(winter,now)),/when Winter begins on 1 December 2026/);
 assert.match(premiumNote(winter,saleWindow(winter,at('2027-03-01T00:00:00Z'))),/keeps it forever/);
 for(const state of ['upcoming','on_sale','ended'])assert.doesNotMatch(premiumNote(winter,{...saleWindow(winter),state})+closedReason('Winter',{...saleWindow(winter),state}),/ - | — |eternia:/);
});

async function harness(t,now,{mapping,seasons}){
 t.mock.method(Date,'now',()=>now.value);
 const user={uuid:'11111111-1111-4111-8111-111111111111',name:'Explorer',admin:true};
 const listing={id:mapping.id,name:mapping.name,type:'single',price:5,currency:'USD'};
 const state={creates:0};
 const basket=()=>({ident:'basket-1',username:user.name,custom:{eternia_profile_uuid:user.uuid},complete:false,packages:[{id:Number(mapping.id),type:'single',is_recurring:false,in_basket:{quantity:1}}],total_price:5,currency:'USD',links:{checkout:'https://pay.tebex.io/basket-1'}});
 const headless={configured:true,packages:async()=>[listing],create:async()=>{state.creates++;return {...basket(),packages:[]};},get:async()=>basket(),add:async()=>basket(),auth:async()=>'https://headless.tebex.io/auth'};
 const bridge={getCrownProducts:async()=>({source:'game',products:[mapping]}),getOverview:async()=>({source:'game',account:{uuid:user.uuid}})};
 const cfg={tebexPackages:[mapping],tebexCheckoutEnabled:true,clientId:'configured',clientSecret:'configured',bridgeUrl:'https://game.example',bridgeToken:'configured'};
 const app=express();app.use(session({secret:'local-test-secret-12345678901234567890',resave:false,saveUninitialized:false}));app.use(express.json());
 app.use((req,res,next)=>{req.session.user=user;next();});
 mountTebexCheckout(app,cfg,headless,bridge,seasons);app.use(errorHandler);
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));
 t.after(async()=>{server.closeAllConnections();await new Promise(r=>server.close(r));});
 let cookie='';
 const request=async(path,body)=>{const response=await fetch('http://127.0.0.1:'+server.address().port+path,{method:body===undefined?'GET':'POST',headers:{Cookie:cookie,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});if(response.headers.has('set-cookie'))cookie=response.headers.get('set-cookie').split(';')[0];return response;};
 return {state,request};
}

test('a seasonal package can only start checkout inside its season, with a clear reason',async t=>{
 const now={value:at('2026-11-20T12:00:00Z')};
 const mapping={id:'7709001',revision:1,crowns:900,name:'Frost Chest',season:winter.id};
 const {state,request}=await harness(t,now,{mapping,seasons:async()=>[winter]});
 let store=await (await request('/api/store')).json();
 assert.equal(store.offers[0].sale.state,'upcoming');assert.equal(store.offers[0].sale.season,'Winter');
 let response=await request('/api/store/checkout',{packageId:mapping.id});
 assert.equal(response.status,409);assert.match((await response.json()).error,/Winter begins on 1 December 2026/);
 assert.equal(state.creates,0,'No basket is created before the season');

 now.value=at('2027-01-30T23:00:00Z');
 response=await request('/api/store/checkout',{packageId:mapping.id});assert.equal(response.status,200);assert.equal(state.creates,1);

 // A stale page after the season: refused before any new basket, and the store shows it ended.
 now.value=at('2027-01-31T00:00:01Z');
 response=await request('/api/store/checkout',{packageId:mapping.id});
 assert.equal(response.status,409);assert.match((await response.json()).error,/Winter has ended/);
 store=await (await request('/api/store')).json();assert.equal(store.offers[0].sale.state,'ended');assert.equal(store.offers[0].sale.purchasable,false);
});

test('ordinary packages are unaffected and an unknown season fails closed',async t=>{
 const now={value:at('2030-01-01T00:00:00Z')};
 const plain=await harness(t,now,{mapping:{id:'7704919',revision:1,crowns:500,name:'Pouch of Crowns'},seasons:async()=>{throw new Error('catalog offline');}});
 assert.equal((await plain.request('/api/store/checkout',{packageId:'7704919'})).status,200);
 assert.equal((await (await plain.request('/api/store')).json()).offers[0].sale,undefined);
 const lost=await harness(t,now,{mapping:{id:'7709002',revision:1,crowns:900,name:'Lost Chest',season:'eternia:missing'},seasons:async()=>[winter]});
 const response=await lost.request('/api/store/checkout',{packageId:'7709002'});assert.equal(response.status,409);assert.equal(lost.state.creates,0);
});
