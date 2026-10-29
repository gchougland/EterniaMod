export class HttpError extends Error { constructor(status,message){super(message);this.status=status;} }
export const route = fn => (req,res,next)=>Promise.resolve(fn(req,res,next)).catch(next);
export function requireUser(req,res,next){if(!req.session?.user)return res.status(401).json({error:'Sign in to continue.'});next();}
export function requireAdmin(req,res,next){if(!req.session?.user?.admin)return res.status(403).json({error:'Administrator access required.'});next();}

export function errorHandler(err,req,res,next){
 if(res.headersSent)return next(err);
 const status=err.status||(err.code==='23505'?409:500);
 // HttpError messages are authored for players. Unexpected server errors stay private.
 res.status(status).json({error:err instanceof HttpError||status<500?err.message:'Service unavailable. Please try again.'});
}

