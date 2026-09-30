package com.hexvane.eterniamod.runtime;

import java.util.Map;

/** Validated server settings; the development mode must be selected explicitly. */
public record RuntimeConfig(boolean local, String databaseUrl, String databaseUser, String databasePassword,
                            String bridgeAddress, int bridgePort, String bridgeToken, String websiteUrl) {
    public static RuntimeConfig from(Map<String,String> env) {
        String mode=env.getOrDefault("ETERNIA_MODE","production");
        if (!mode.equals("local")&&!mode.equals("production")) throw new IllegalArgumentException("ETERNIA_MODE must be local or production");
        boolean local=mode.equals("local");
        String database=env.getOrDefault("ETERNIA_DATABASE_URL","");
        if(!database.isBlank()&&!database.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("ETERNIA_DATABASE_URL must be jdbc:postgresql://host/database");
        if (!local&&!database.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("Production requires ETERNIA_DATABASE_URL=jdbc:postgresql://host/database. Set it in eternia-server.properties inside the mod data folder or in the server environment. ETERNIA_MODE=local is only for isolated local testing.");
        String token=env.getOrDefault("ETERNIA_BRIDGE_TOKEN","");
        if (!token.isEmpty()&&token.length()<32) throw new IllegalArgumentException("ETERNIA_BRIDGE_TOKEN must contain at least 32 characters");
        String address=env.getOrDefault("ETERNIA_BRIDGE_ADDRESS","127.0.0.1");
        if (local&&!address.equals("127.0.0.1")&&!address.equals("::1")) throw new IllegalArgumentException("Local bridge must bind to loopback");
        int port=Integer.parseInt(env.getOrDefault("ETERNIA_BRIDGE_PORT","9010"));
        if (port<1||port>65535) throw new IllegalArgumentException("Invalid bridge port");
        String website=env.getOrDefault("ETERNIA_WEBSITE_URL",local?"http://127.0.0.1:3847":"");
        if (!website.isBlank()) {
            java.net.URI uri=java.net.URI.create(website);
            if (uri.getHost()==null||uri.getUserInfo()!=null||!(uri.getScheme().equals("https")||local&&uri.getScheme().equals("http")&&java.util.Set.of("localhost","127.0.0.1","::1").contains(uri.getHost())))
                throw new IllegalArgumentException("Website URL requires HTTPS (loopback HTTP allowed in local mode)");
        }
        return new RuntimeConfig(local,database,env.getOrDefault("ETERNIA_DATABASE_USER",""),env.getOrDefault("ETERNIA_DATABASE_PASSWORD",""),address,port,token,website);
    }
    /** An explicit database URL exercises PostgreSQL even in local development mode. */
    public boolean postgres() { return !databaseUrl.isBlank(); }
    @Override public String toString() { return "RuntimeConfig[mode="+(local?"local":"production")+", storage="+(postgres()?"postgres":"file")+", secrets=redacted]"; }
}
