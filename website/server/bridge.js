import { HttpError } from './errors.js';
export const FIXTURE_UUID='11111111-1111-4111-8111-111111111111';
export function createBridge(cfg) {
 return { async getOverview(uuid){
  if(cfg.fixtures)return {source:'local-fixture',updatedAt:new Date().toISOString(),account:{name:'Local Explorer',rank:'Citizen'},crowns:{available:5000,owed:0},coins:{available:2000,reserved:0},stats:{playtimeHours:12,mobsDefeated:48,resourcesGathered:320},owned:[{id:'eternia:service/hub_intro',name:'Hidden service grant',kind:'unlock',quantity:1},{id:'eternia:house/hub_house',name:'Hub House',kind:'unlock',quantity:1},{id:'eternia:house/hearthstone_manor',name:'Hearthstone Manor',kind:'unlock',quantity:1},{id:'eternia:prop/aqua_lamp',name:'Aqua Lamp',kind:'quantity',quantity:0,placed:1},{id:'eternia:prop/fire_pit',name:'Fire Pit',kind:'quantity',quantity:2,packed:1},{id:'eternia:palette/cut_stone',name:'Cut Stone',kind:'unlock',quantity:1},{id:'eternia:path/cobblestone',name:'Cobblestone',kind:'unlock',quantity:1},{id:'eternia:title/wayfarer',name:'Wayfarer',kind:'unlock',quantity:1},{id:'eternia:wearable/citadel_jacket',name:'Citadel Jacket',kind:'unlock',quantity:1},{id:'eternia:pet/corn_buddy',name:'Corn Buddy',kind:'quantity',quantity:1},{id:'eternia:pet/rootling',name:'Rootling',kind:'quantity',quantity:1},{id:'eternia:mount/horse',name:'Horse',kind:'unlock',quantity:1},{id:'eternia:plot/size_24',name:'24 × 24 Housing Plot',kind:'capability',quantity:1},{id:'eternia:plot_move_credit',name:'Plot Move Credit',kind:'quantity',quantity:0}],seasons:[{id:'foundations',name:'Foundations',xp:2400,totalXp:60000,paid:false}],housing:{state:'unclaimed'}};
  return request('/v1/players/'+encodeURIComponent(uuid)+'/overview');
 },async getOffers(){if(cfg.fixtures)return {source:'local-fixture',offers:[]};return request('/v1/store/offers');},
 async getCrownProducts(){if(cfg.fixtures)return {source:'local-fixture',products:[]};return request('/v1/store/crowns');}};
 async function request(endpoint){
  if(!cfg.bridgeUrl||!cfg.bridgeToken)throw new HttpError(503,'Game account connection is not available yet.');
  const res=await fetch(cfg.bridgeUrl.replace(/\/$/,'')+endpoint,{headers:{Authorization:'Bearer '+cfg.bridgeToken},signal:AbortSignal.timeout(5000)});
  if(res.status===404&&endpoint.endsWith('/overview'))throw new HttpError(409,'Join Eternia once with this Hytale profile before buying Crowns.');
  if(!res.ok)throw new HttpError(503,'Game account service is temporarily unavailable.');
  return res.json();
 }
}
