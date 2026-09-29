export class HttpError extends Error { constructor(status,message){super(message);this.status=status;} }
export const route = fn => (req,res,next)=>Promise.resolve(fn(req,res,next)).catch(next);
export function requireUser(req,res,next){if(!req.session?.user)return res.status(401).json({error:'Sign in to continue.'});next();}
export function requireAdmin(req,res,next){if(!req.session?.user?.admin)return res.status(403).json({error:'Administrator access required.'});next();}

