import {isIP} from 'node:net';
import {HttpError} from './errors.js';

const API='https://headless.tebex.io/api';
const IDENT=/^[a-zA-Z0-9-]{8,160}$/;
export function safeTebexUrl(value){
 try{const u=new URL(value);return u.protocol==='https:'&&!u.username&&!u.password&&(u.hostname==='tebex.io'||u.hostname.endsWith('.tebex.io'))?u.href:null;}catch{return null;}
}
export function createHeadless(cfg,fetcher=fetch){
 const token=cfg.tebexPublicToken,account='/accounts/'+encodeURIComponent(token||'');
 async function request(endpoint,body){
  if(!token)throw new HttpError(503,'The Crown treasury is not connected yet.');
  const headers={Accept:'application/json'};
  if(cfg.tebexPrivateKey)headers.Authorization='Basic '+Buffer.from(token+':'+cfg.tebexPrivateKey).toString('base64');
  if(body)headers['Content-Type']='application/json';
  try{
   const response=await fetcher(API+endpoint,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(10000)});
   if(!response.ok)throw new Error('Provider unavailable');
   const reader=response.body.getReader(),parts=[];let bytes=0;
   for(;;){const part=await reader.read();if(part.done)break;bytes+=part.value.length;if(bytes>1024*1024){await reader.cancel();throw new Error('Response too large');}parts.push(part.value);}
   return JSON.parse(Buffer.concat(parts).toString('utf8'));
  }catch{throw new HttpError(503,'Tebex is temporarily unavailable. Please try again.');}
 }
 function basketId(ident){if(typeof ident!=='string'||!IDENT.test(ident))throw new HttpError(502,'Checkout could not be prepared.');return encodeURIComponent(ident);}
 return {
  configured:Boolean(token),
  async packages(){const result=await request(account+'/packages');if(!Array.isArray(result.data))throw new HttpError(502,'The Crown treasury could not be loaded.');
   return result.data.filter(p=>/^\d+$/.test(String(p.id))&&typeof p.name==='string').map(p=>({id:String(p.id),name:p.name.slice(0,160),type:p.type,
    price:p.total_price,currency:p.currency,category:p.category?.name||'',requiresOptions:Boolean(p.options?.length||p.variables?.length)}));},
  async create(user,ip){
   if(!isIP(ip))throw new HttpError(400,'Your connection address could not be verified.');
   const result=await request(account+'/baskets',{username:user.name,ip_address:ip,
    complete_url:cfg.baseUrl+'/store?checkout=returned',cancel_url:cfg.baseUrl+'/store?checkout=cancelled',complete_auto_redirect:false,
    custom:{eternia_profile_uuid:user.uuid}});
   basketId(result.data?.ident);return result.data;
  },
  async get(ident){const result=await request(account+'/baskets/'+basketId(ident));if(result.data?.ident!==ident)throw new HttpError(502,'Checkout could not be verified.');return result.data;},
  async auth(ident,state){const result=await request(account+'/baskets/'+basketId(ident)+'/auth?returnUrl='+encodeURIComponent(cfg.baseUrl+'/store?checkout=authorize&state='+state));
   const links=Array.isArray(result)?result:result.data;
   const hytale=Array.isArray(links)?links.find(item=>/hytale/i.test(item.name)&&safeTebexUrl(item.url)):null;
   if(!hytale)throw new HttpError(409,'Tebex could not verify your Hytale profile. Please try again or contact the server team.');
   return safeTebexUrl(hytale.url);},
  async add(ident,id){await request('/baskets/'+basketId(ident)+'/packages',{package_id:id,quantity:1});return this.get(ident);}
 };
}

export function checkoutView(basket,pending,user){
 // username_id can be Tebex's internal numeric account ID. Never interpret it as a Hytale UUID.
 if(typeof basket.username!=='string'||basket.username.toLowerCase()!==user.name.toLowerCase())throw new HttpError(409,'Use the same Hytale profile for Eternia and Tebex.');
 if(basket.custom?.eternia_profile_uuid!==user.uuid)throw new HttpError(409,'This checkout belongs to another profile.');
 if(basket.complete)throw new HttpError(409,'This checkout is already complete. Check your balance before making another purchase.');
 const items=basket.packages;
 if(!Array.isArray(items)||items.length!==1||String(items[0].id)!==pending.packageId||Number(items[0].in_basket?.quantity)!==1||items[0].in_basket?.gift_username_id||items[0].is_recurring||items[0].type!=='single')
  throw new HttpError(409,'Your checkout changed. Please choose a Crown package again.');
 if(!Number.isFinite(basket.total_price)||basket.total_price<0||!/^[A-Z]{3}$/.test(basket.currency))throw new HttpError(502,'Checkout price could not be verified.');
 const checkoutUrl=safeTebexUrl(basket.links?.checkout);if(!checkoutUrl)throw new HttpError(502,'Checkout could not be opened.');
 return {status:'ready',ident:basket.ident,checkoutUrl,packageId:pending.packageId,price:basket.total_price,currency:basket.currency};
}
