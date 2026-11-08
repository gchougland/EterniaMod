// Public "adventurers online" count for the welcome page. Only a number is ever read from the game.
// One shared cached answer serves every visitor, so traffic never reaches the game server directly.
export function createPlayersOnline(bridge,{ttl=30000,offlineTtl=15000,now=Date.now}={}){
 let cached,expires=0,inflight;
 async function load(){
  try{
   const result=await bridge.getPlayersOnline();
   const online=Number(result?.online);
   if(!Number.isSafeInteger(online)||online<0||online>1000000)throw new Error('Invalid count');
   return {status:'online',online,updatedAt:new Date(now()).toISOString()};
  }catch{
   // Keep any detail private. The page shows a calm resting state instead of an error.
   return {status:'resting',updatedAt:new Date(now()).toISOString()};
  }
 }
 async function get(){
  if(cached&&now()<expires)return cached;
  if(!inflight)inflight=load().then(value=>{cached=value;expires=now()+(value.status==='online'?ttl:offlineTtl);return value;}).finally(()=>{inflight=null;});
  return inflight;
 }
 return {get};
}
export function mountPlayersOnline(app,bridge,options){
 const counter=createPlayersOnline(bridge,options);
 app.get('/api/v1/players-online',async(req,res)=>{
  const value=await counter.get();
  res.set('Cache-Control','public, max-age=15').json(value);
 });
 return counter;
}
