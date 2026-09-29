import express from 'express';
/** Only the game verifies payment authority. Preserve the exact signed bytes and never forward browser headers. */
export function mountTebexRelay(app,cfg){
 let active=0;
 app.all('/webhooks/tebex',(req,res,next)=>{
  res.set('Cache-Control','no-store');
  if(req.method!=='POST')return res.status(405).json({status:'post_required'});
  if(!cfg.bridgeUrl||!cfg.bridgeToken)return res.status(503).json({status:'temporarily_unavailable'});
  if(!/^[a-f\d]{64}$/i.test(req.get('X-Signature')||''))return res.status(401).json({status:'invalid_signature'});
  if(active>=8)return res.status(429).json({status:'retry_later'});
  active++;let released=false;const release=()=>{if(!released){released=true;active--;}};res.once('finish',release);res.once('close',release);
  req.setTimeout(10000,()=>req.destroy());
  next();
 },(req,res,next)=>express.raw({type:()=>true,limit:'1mb',inflate:false})(req,res,error=>{
  if(error)return res.status(error.status===413?413:400).json({status:'invalid_request'});
  next();
 }),async(req,res)=>{
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),8000);
  res.once('close',()=>{if(!res.writableEnded)controller.abort();});
  try{
   const upstream=await fetch(cfg.bridgeUrl.replace(/\/$/,'')+'/v1/commerce/tebex',{method:'POST',redirect:'error',signal:controller.signal,
    headers:{'Content-Type':'application/json','X-Signature':req.get('X-Signature'),Authorization:'Bearer '+cfg.bridgeToken},body:req.body});
   const reader=upstream.body?.getReader();let size=0;const chunks=[];
   if(reader)for(;;){const part=await reader.read();if(part.done)break;size+=part.value.length;if(size>65536){await reader.cancel();throw new Error('Response limit');}chunks.push(part.value);}
   const result=JSON.parse(Buffer.concat(chunks).toString('utf8'));
   // Return only the provider protocol fields, never arbitrary upstream diagnostics or headers.
   const output=typeof result.id==='string'&&result.id.length<=200?{id:result.id}:{status:typeof result.status==='string'&&/^[a-z_]{1,60}$/.test(result.status)?result.status:'temporarily_unavailable'};
   res.status(upstream.status>=200&&upstream.status<500?upstream.status:503).json(output);
  }catch{if(!res.headersSent)res.status(503).json({status:'temporarily_unavailable'});}
  finally{clearTimeout(timer);}
 });
}
