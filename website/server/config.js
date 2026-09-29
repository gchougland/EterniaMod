import path from 'node:path';
import { fileURLToPath } from 'node:url';
export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export function config(env = process.env) {
 const production = env.NODE_ENV === 'production' || Boolean(env.RAILWAY_ENVIRONMENT);
 const fixtures = env.LOCAL_FIXTURES === 'true';
 if (production && fixtures) throw new Error('Local fixtures forbidden in production');
 const baseUrl = new URL(env.PUBLIC_BASE_URL || 'http://127.0.0.1:3847');
 if (production && baseUrl.protocol !== 'https:') throw new Error('Production requires HTTPS PUBLIC_BASE_URL');
 if (fixtures && !['127.0.0.1','localhost','[::1]'].includes(baseUrl.hostname)) throw new Error('Fixtures require a loopback origin');
 const secret = env.SESSION_SECRET || (fixtures ? 'local-fixture-secret-never-for-production' : '');
 if (secret.length < 32) throw new Error('SESSION_SECRET must contain at least 32 characters');
 if (!fixtures && !env.DATABASE_URL) throw new Error('DATABASE_URL required; use explicit LOCAL_FIXTURES for isolated local testing');
 const issuer = env.HYTALE_OIDC_ISSUER || 'https://connect.accounts.hytale.com';
 if (new URL(issuer).protocol !== 'https:') throw new Error('OIDC issuer requires HTTPS');
 if (production && issuer !== 'https://connect.accounts.hytale.com') throw new Error('Production accepts only the Hytale issuer; fixture issuers are forbidden');
 const redirectUri = env.HYTALE_OIDC_REDIRECT_URI || baseUrl.origin+'/auth/callback';
 const redirect = new URL(redirectUri);
 if (redirect.origin !== baseUrl.origin || redirect.pathname !== '/auth/callback' || redirect.search || redirect.hash) throw new Error('OAuth callback must match PUBLIC_BASE_URL/auth/callback');
 const bridgeUrl = env.GAME_BRIDGE_URL || '';
 if (bridgeUrl && production && new URL(bridgeUrl).protocol !== 'https:') throw new Error('Production bridge requires HTTPS');
 return {production, fixtures, baseUrl:baseUrl.origin, port:Number(env.PORT || 3847), host:production ? '0.0.0.0' : '127.0.0.1', secret,
 databaseUrl:env.DATABASE_URL, dataDir:path.resolve(root,env.DATA_DIR || 'data'),
 assetsDir:path.resolve(root,env.HYTALE_ASSETS_DIR || 'web/hytale-assets'), chromiumPath:env.CHROMIUM_PATH || undefined,
 issuer,clientId:env.HYTALE_OIDC_CLIENT_ID || '',clientSecret:env.HYTALE_OIDC_CLIENT_SECRET || '',
 redirectUri,
 admins:new Set((env.ADMIN_HYTALE_UUIDS || '').toLowerCase().split(',').map(x=>x.trim()).filter(Boolean)),bridgeUrl,bridgeToken:env.GAME_BRIDGE_TOKEN || ''};
}
