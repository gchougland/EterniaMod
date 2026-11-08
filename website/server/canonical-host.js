// Send visitors on the www twin of the site's address (or the bare twin of a www address) to the one
// public address. Sessions, sign in callbacks and form origin checks all expect PUBLIC_BASE_URL exactly.
export function twinHost(baseUrl){
 const host=new URL(baseUrl).host.toLowerCase();
 if(/^(\d+\.){3}\d+(:\d+)?$/.test(host)||host.startsWith('['))return null;
 return host.startsWith('www.')?host.slice(4):'www.'+host;
}
export function canonicalHost(cfg){
 const twin=twinHost(cfg.baseUrl);
 return (req,res,next)=>{
  const host=(req.get('X-Forwarded-Host')&&cfg.production?req.get('X-Forwarded-Host').split(',')[0]:req.get('Host')||'').trim().toLowerCase();
  if(!twin||host!==twin)return next();
  // 308 keeps the method and body for anything that is not a plain page load.
  res.redirect(['GET','HEAD'].includes(req.method)?301:308,cfg.baseUrl+req.originalUrl);
 };
}
