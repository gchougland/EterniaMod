import {isIP} from 'node:net';
import {HttpError} from './errors.js';

const API='https://headless.tebex.io/api';
const IDENT=/^[a-zA-Z0-9-]{8,160}$/;
function providerDiagnostic(payload,sensitive){
 if(!payload||typeof payload!=='object'||Array.isArray(payload))return {};
 const values=sensitive.filter(value=>typeof value==='string'&&value.length)
  .flatMap(value=>[value,encodeURIComponent(value),JSON.stringify(value).slice(1,-1)])
  .sort((a,b)=>b.length-a.length);
 const redact=value=>{
  if(typeof value!=='string')return undefined;
  for(const secret of values)value=value.split(secret).join('[redacted]');
  return value.replace(/https?:\/\/[^\s"'<>]+/gi,'[url]')
   .replace(/[\w.+-]+@[\w.-]+\.[a-z]{2,}/gi,'[email]')
   .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi,'[profile]')
   .replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g,'[ip]')
   .replace(/[\u0000-\u001f\u007f]/g,' ').slice(0,500);
 };
 return {title:redact(payload.title),detail:redact(payload.detail),code:redact(payload.error_code)};
}
function bodyStrings(value){
 if(typeof value==='string')return [value];
 return value&&typeof value==='object'?Object.values(value).flatMap(bodyStrings):[];
}
function basketField(value){
 // Only known format values can reach logs, never arbitrary provider strings or objects.
 const type=value===null?'null':typeof value;
 if(typeof value==='number'&&Number.isFinite(value)||typeof value==='boolean'||value===null)return {type,value};
 if(typeof value==='string'&&(/^[0-9]{1,20}$/.test(value)||['single','subscription','both','false','true',''].includes(value)))return {type,value};
 return {type};
}
export function safeTebexUrl(value){
 try{const u=new URL(value);return u.protocol==='https:'&&!u.username&&!u.password&&(u.hostname==='tebex.io'||u.hostname.endsWith('.tebex.io'))?u.href:null;}catch{return null;}
}
export function createHeadless(cfg,fetcher=fetch){
 const token=cfg.tebexPublicToken,account='/accounts/'+encodeURIComponent(token||'');
 async function request(operation,endpoint,body){
  if(!token)throw new HttpError(503,'The Crown treasury is not connected yet.');
  const headers={Accept:'application/json'};
  if(cfg.tebexPrivateKey)headers.Authorization='Basic '+Buffer.from(token+':'+cfg.tebexPrivateKey).toString('base64');
  if(body)headers['Content-Type']='application/json';
  const authConfigured=Boolean(headers.Authorization);
  let status=null,failure='connection',provider={};
  try{
   const response=await fetcher(API+endpoint,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(10000)});
   status=response.status;failure=response.ok?'response':'http';
   const reader=response.body.getReader(),parts=[];let bytes=0;
   const maxBytes=response.ok?1024*1024:8192;
   for(;;){const part=await reader.read();if(part.done)break;bytes+=part.value.length;if(bytes>maxBytes){await reader.cancel();throw new Error('Response too large');}parts.push(part.value);}
   const payload=JSON.parse(Buffer.concat(parts).toString('utf8'));
   if(!response.ok){
    provider=providerDiagnostic(payload,[token,cfg.tebexPrivateKey,headers.Authorization,headers.Authorization?.slice(6),endpoint,endpoint.match(/\/baskets\/([^/?]+)/)?.[1],...bodyStrings(body)]);
    throw new Error('Provider rejected request');
   }
   return payload;
  }catch{
   // Log only bounded, redacted provider explanations. Never log request data or raw exceptions.
   console.warn('Tebex request failed',JSON.stringify({operation,status,failure,authConfigured,...provider}));
   throw new HttpError(503,'Tebex could not prepare your purchase. Please try again later.');
  }
 }
 function basketId(ident){if(typeof ident!=='string'||!IDENT.test(ident))throw new HttpError(502,'Checkout could not be prepared.');return encodeURIComponent(ident);}
 return {
  configured:Boolean(token),
  async packages(){const result=await request('catalog',account+'/packages');if(!Array.isArray(result.data))throw new HttpError(502,'The Crown treasury could not be loaded.');
   return result.data.filter(p=>/^\d+$/.test(String(p.id))&&typeof p.name==='string').map(p=>({id:String(p.id),name:p.name.slice(0,160),type:p.type,
    price:p.total_price,currency:p.currency,category:p.category?.name||'',requiresOptions:Boolean(p.options?.length||p.variables?.length)}));},
  async create(user,ip){
   if(!isIP(ip))throw new HttpError(400,'Your connection address could not be verified.');
   const result=await request('create_basket',account+'/baskets',{username:user.name,ip_address:ip,
    complete_url:cfg.baseUrl+'/store?checkout=returned',cancel_url:cfg.baseUrl+'/store?checkout=cancelled',complete_auto_redirect:false,
    custom:{eternia_profile_uuid:user.uuid}});
   basketId(result.data?.ident);return result.data;
  },
  async get(ident){const result=await request('read_basket',account+'/baskets/'+basketId(ident));if(result.data?.ident!==ident)throw new HttpError(502,'Checkout could not be verified.');return result.data;},
  async auth(ident,state){const result=await request('authorize_basket',account+'/baskets/'+basketId(ident)+'/auth?returnUrl='+encodeURIComponent(cfg.baseUrl+'/store?checkout=authorize&state='+state));
   const links=Array.isArray(result)?result:result.data;
   const hytale=Array.isArray(links)?links.find(item=>/hytale/i.test(item.name)&&safeTebexUrl(item.url)):null;
   if(!hytale)throw new HttpError(409,'Tebex could not verify your Hytale profile. Please try again or contact the server team.');
   return safeTebexUrl(hytale.url);},
  async add(ident,id){await request('add_package','/baskets/'+basketId(ident)+'/packages',{package_id:id,quantity:1});return this.get(ident);}
 };
}

export function checkoutView(basket,pending,user){
 // username_id can be Tebex's internal numeric account ID. Never interpret it as a Hytale UUID.
 if(typeof basket.username!=='string'||basket.username.toLowerCase()!==user.name.toLowerCase())throw new HttpError(409,'Use the same Hytale profile for Eternia and Tebex.');
 if(basket.custom?.eternia_profile_uuid!==user.uuid)throw new HttpError(409,'This checkout belongs to another profile.');
 if(basket.complete)throw new HttpError(409,'This checkout is already complete. Check your balance before making another purchase.');
 const items=basket.packages;
 const item=Array.isArray(items)&&items.length===1?items[0]:null;
 const reasons=[];
 if(!Array.isArray(items)||items.length!==1)reasons.push('package_count');
 else{
  if(!item||String(item.id)!==pending.packageId)reasons.push('package_id');
  if(Number(item?.in_basket?.quantity)!==1)reasons.push('quantity');
  if(item?.in_basket?.gift_username_id)reasons.push('gift');
  if(item?.is_recurring)reasons.push('recurring');
  // Tebex can omit the catalog type from basket items. Require explicit nonrecurrence in that case.
  if(item?.type!=='single'&&!(item?.type===undefined&&item?.is_recurring===false))reasons.push('purchase_type');
 }
 if(reasons.length){
  console.warn('Tebex basket validation failed',JSON.stringify({reasons,packageCount:Array.isArray(items)?items.length:null,
   packageIdMatches:!!item&&String(item.id)===pending.packageId,quantity:basketField(item?.in_basket?.quantity),
   purchaseType:basketField(item?.type),recurring:basketField(item?.is_recurring),
   gift:{present:Boolean(item?.in_basket?.gift_username_id),type:typeof item?.in_basket?.gift_username_id}}));
  throw new HttpError(409,'Your checkout changed. Please choose a Crown package again.');
 }
 if(!Number.isFinite(basket.total_price)||basket.total_price<0||!/^[A-Z]{3}$/.test(basket.currency))throw new HttpError(502,'Checkout price could not be verified.');
 const checkoutUrl=safeTebexUrl(basket.links?.checkout);if(!checkoutUrl)throw new HttpError(502,'Checkout could not be opened.');
 return {status:'ready',ident:basket.ident,checkoutUrl,packageId:pending.packageId,price:basket.total_price,currency:basket.currency};
}
