import crypto from 'node:crypto';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { HttpError } from './errors.js';
const encode = value=>Buffer.from(value).toString('base64url');
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function createOidc(cfg,fetcher=fetch){
 let discovery,keys;
 const enabled=Boolean(cfg.clientId&&cfg.clientSecret&&cfg.redirectUri);
 async function discover(){
  if(!enabled) throw new HttpError(503,'Hytale sign-in is not configured yet.');
  if(!discovery){const response=await fetcher(cfg.issuer.replace(/\/$/,'')+'/.well-known/openid-configuration',{signal:AbortSignal.timeout(10000)});
   if(!response.ok) throw new HttpError(502,'Identity provider unavailable.');
   const doc=await response.json();
   if(doc.issuer!==cfg.issuer)throw new Error('Unexpected identity issuer');
   for(const key of ['authorization_endpoint','token_endpoint','userinfo_endpoint','jwks_uri'])if(new URL(doc[key]).protocol!=='https:')throw new Error('Unsafe provider endpoint');
   discovery=doc;keys=createRemoteJWKSet(new URL(doc.jwks_uri));}
  return discovery;
 }
 return {enabled, async start(){
  const doc=await discover(),state=encode(crypto.randomBytes(32)),verifier=encode(crypto.randomBytes(32)),nonce=encode(crypto.randomBytes(32));
  const url=new URL(doc.authorization_endpoint);url.search=new URLSearchParams({response_type:'code',client_id:cfg.clientId,redirect_uri:cfg.redirectUri,scope:'openid hytale:profile',state,nonce,code_challenge:encode(crypto.createHash('sha256').update(verifier).digest()),code_challenge_method:'S256'});
  return {url:url.href,pending:{state,verifier,nonce,createdAt:Date.now()}};
 },async finish(query,pending){
  if(!pending||Date.now()-pending.createdAt>300000||typeof query.state!=='string'||query.state.length!==pending.state.length||!crypto.timingSafeEqual(Buffer.from(query.state),Buffer.from(pending.state))||typeof query.code!=='string')throw new HttpError(400,'Login expired or invalid. Please sign in again.');
  const doc=await discover();
  const response=await fetcher(doc.token_endpoint,{method:'POST',signal:AbortSignal.timeout(10000),headers:{'Content-Type':'application/x-www-form-urlencoded',Authorization:'Basic '+Buffer.from(cfg.clientId+':'+cfg.clientSecret).toString('base64')},body:new URLSearchParams({grant_type:'authorization_code',code:query.code,redirect_uri:cfg.redirectUri,code_verifier:pending.verifier})});
  if(!response.ok)throw new HttpError(401,'Hytale login could not be completed.');
  const token=await response.json();
  if(!token.id_token||!token.access_token)throw new HttpError(401,'Provider did not return required tokens.');
  const {payload}=await jwtVerify(token.id_token,keys,{issuer:cfg.issuer,audience:cfg.clientId,algorithms:['RS256','ES256']});
  if(payload.nonce!==pending.nonce)throw new HttpError(401,'Invalid login nonce.');
  const userRes=await fetcher(doc.userinfo_endpoint,{headers:{Authorization:'Bearer '+token.access_token},signal:AbortSignal.timeout(10000)});
  if(!userRes.ok)throw new HttpError(401,'Unable to verify Hytale profile.');
  const info=await userRes.json();
  if(info.sub!==payload.sub)throw new HttpError(401,'Profile subject does not match login.');
  const profile=info.profile||{}, uuid=String(profile.uuid||'').toLowerCase();
  if(!UUID.test(uuid))throw new HttpError(401,'No verified Hytale game profile UUID.');
  return {uuid,name:String(profile.username||'Player').slice(0,64),subject:payload.sub,admin:cfg.admins.has(uuid)};
 }};
}
export const regenerate = req=>new Promise((resolve,reject)=>req.session.regenerate(err=>err?reject(err):resolve()));
export const saveSession = req=>new Promise((resolve,reject)=>req.session.save(err=>err?reject(err):resolve()));

