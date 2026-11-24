import { HttpError } from './errors.js';import {withPlotSize} from './plot-size.js';
export const FIXTURE_UUID='11111111-1111-4111-8111-111111111111';
export function createBridge(cfg) {
 return { async getOverview(uuid){
  if(cfg.fixtures)return {source:'local-fixture',updatedAt:new Date().toISOString(),account:{name:'Local Explorer',rank:'Citizen'},crowns:{available:5000,owed:0},coins:{available:2000,reserved:0},stats:{playtimeHours:12,mobsDefeated:48,resourcesGathered:320},owned:[{id:'eternia:service/hub_intro',name:'Hidden service grant',kind:'unlock',quantity:1},{id:'eternia:house/hub_house',name:'Hub House',kind:'unlock',quantity:1},{id:'eternia:house/hearthstone_manor',name:'Hearthstone Manor',kind:'unlock',quantity:1},{id:'eternia:prop/aqua_lamp',name:'Aqua Lamp',kind:'quantity',quantity:0,placed:1},{id:'eternia:prop/fire_pit',name:'Fire Pit',kind:'quantity',quantity:2,packed:1},{id:'eternia:palette/cut_stone',name:'Cut Stone',kind:'unlock',quantity:1},{id:'eternia:path/cobblestone',name:'Cobblestone',kind:'unlock',quantity:1},{id:'eternia:title/wayfarer',name:'Wayfarer',kind:'unlock',quantity:1},{id:'eternia:wearable/citadel_jacket',name:'Citadel Jacket',kind:'unlock',quantity:1},{id:'eternia:pet/corn_buddy',name:'Corn Buddy',kind:'quantity',quantity:1},{id:'eternia:pet/rootling',name:'Rootling',kind:'quantity',quantity:1},{id:'eternia:mount/horse',name:'Horse',kind:'unlock',quantity:1},{id:'eternia:plot/size_24',name:'24 × 24 Housing Plot',kind:'capability',quantity:1},{id:'eternia:plot_move_credit',name:'Plot Move Credit',kind:'quantity',quantity:0}],seasons:[{id:'foundations',name:'Foundations',xp:2400,totalXp:60000,paid:false}],housing:{state:'unclaimed'}};
  // Hex plots arrive as cells; pages read cellCount and sizeLabel instead of working it out again.
  const overview=await request('/v1/players/'+encodeURIComponent(uuid)+'/overview');
  return overview&&typeof overview==='object'&&overview.housing?{...overview,housing:withPlotSize(overview.housing)}:overview;
 },async getOffers(){if(cfg.fixtures)return {source:'local-fixture',offers:[]};return request('/v1/store/offers');},
 async getCrownProducts(){if(cfg.fixtures)return {source:'local-fixture',products:[]};return request('/v1/store/crowns');},
 // Only a head count crosses the bridge. A negative fixture count simulates an offline realm.
 // Analytics come back as finished CSV text. Reports read every player, so they get longer than a page load.
 async getAnalytics(report,from,to){if(cfg.fixtures&&!cfg.bridgeUrl)return 'report,from,to\r\n'+report+','+from+','+to+'\r\n';
  if(!cfg.bridgeUrl||!cfg.bridgeToken)throw new HttpError(503,'Game server connection is not available yet.');
  let res;try{res=await fetch(cfg.bridgeUrl.replace(/\/$/,'')+'/v1/analytics?'+new URLSearchParams({report,from,to}),{headers:{Authorization:'Bearer '+cfg.bridgeToken},signal:AbortSignal.timeout(60000)});}
  catch{throw new HttpError(503,'The game server did not answer. Try again in a minute.');}
  if(res.status===404)throw new HttpError(503,'The game server does not have analytics yet. Update the mod first.');
  if(res.status===400){let why='';try{why=String((await res.json()).error||'');}catch{}throw new HttpError(400,why.slice(0,200)||'Check the dates.');}
  if(!res.ok)throw new HttpError(503,'The game server could not build that report right now.');
  return res.text();},
 async getPlayersOnline(){if(cfg.fixtures&&!cfg.bridgeUrl){if(cfg.fixturePlayersOnline<0)throw new HttpError(503,'Realm offline');return {source:'local-fixture',online:cfg.fixturePlayersOnline};}return request('/v1/status/players');}};
 async function request(endpoint){
  if(!cfg.bridgeUrl||!cfg.bridgeToken)throw new HttpError(503,'Game account connection is not available yet.');
  const res=await fetch(cfg.bridgeUrl.replace(/\/$/,'')+endpoint,{headers:{Authorization:'Bearer '+cfg.bridgeToken},signal:AbortSignal.timeout(5000)});
  if(res.status===404&&endpoint.endsWith('/overview'))throw new HttpError(409,'Join Eternia once with this Hytale profile before buying Crowns.');
  if(!res.ok)throw new HttpError(503,'Game account service is temporarily unavailable.');
  return res.json();
 }
}
