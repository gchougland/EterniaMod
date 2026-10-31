package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.*;
import java.nio.file.Path;
import java.util.UUID;

public final class EterniaRuntime implements AutoCloseable {
    private final TransactionalStore store;
    private final EterniaServices services;
    private final RuntimeConfig config;
    private GameBridgeServer bridge;
    private final java.util.List<StoreOffers.Offer> offers;
    public EterniaRuntime(Path data,RuntimeConfig config) {
        this.config=config;
        if (!config.postgres()) store=new LocalFileStore(data.resolve("local-authority.bin"));
        else {
            var pool=DatabasePool.open(new DriverDataSource(config));
            var jdbc=new JdbcStore(pool);
            try { jdbc.migrate(); } catch(RuntimeException | Error failure) { pool.close(); throw failure; }
            store=jdbc;
        }
        services=new EterniaServices(store);
        try {
            GameplayContent.load(services,data);
            offers=StoreOffers.load(data.resolve("store-offers.json"),services);
        } catch(RuntimeException failure) {
            if(store instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    public void startBridge(com.sun.net.httpserver.HttpHandler tebex) throws java.io.IOException {
        startBridge(tebex,java.util.Map.of());
    }
    public void startBridge(com.sun.net.httpserver.HttpHandler tebex,java.util.Map<String,Integer> packages) throws java.io.IOException {
        if (!config.bridgeToken().isEmpty()) {
            bridge=new GameBridgeServer(services,config);
            bridge.setOffers(offers);
            if(tebex!=null)bridge.setTebexPackages(packages);
            if(tebex!=null)bridge.mount("/v1/commerce/tebex",tebex);
            bridge.start();
        }
    }
    public EterniaServices services(){return services;}
    public RuntimeConfig config(){return config;}
    /** Each source receipt is stable: retries and repeated joins cannot mint more starter content. */
    public void welcome(UUID player,String name) {services.batch(()->{welcomeInBatch(player,name);return null;});}
    private void welcomeInBatch(UUID player,String name) {
        services.welcomePlayer(player,name);
        Owner owner=Owner.player(player);
        grant(owner,"eternia:plot/free",OwnershipService.Kind.CAPABILITY);
        grant(owner,HousingService.MOVE_CREDIT,OwnershipService.Kind.QUANTITY);
        grant(owner,"eternia:house/hub_house",OwnershipService.Kind.UNLOCK);
        grant(owner,"eternia:mount/horse",OwnershipService.Kind.UNLOCK);
        grant(owner,"eternia:service/mailbox",OwnershipService.Kind.UNLOCK);
        grant(owner,"eternia:prop/aqua_lamp",OwnershipService.Kind.QUANTITY);
        grant(owner,"eternia:palette/cut_stone",OwnershipService.Kind.UNLOCK);
        grant(owner,"eternia:path/cobblestone",OwnershipService.Kind.UNLOCK);
        if(services.seasons().progress(player).stream().noneMatch(SeasonService.Progress::active))
            services.seasons().definitions().stream().findFirst().ifPresent(def->services.seasons().select(player,def.id()));
    }
    private void grant(Owner owner,String content,OwnershipService.Kind kind) {
        services.ownership().grant(new OwnershipService.GrantRequest("starter-v1:"+owner.key()+":"+content,owner,content,kind,1,null));
    }
    public void welcomeGuild(UUID guild) {services.batch(()->{welcomeGuildInBatch(guild);return null;});}
    private void welcomeGuildInBatch(UUID guild) {
        if(services.guilds().find(guild).isEmpty())throw new IllegalArgumentException("Guild does not exist");
        Owner owner=Owner.guild(guild);
        grant(owner,"eternia:house/founders_hall",OwnershipService.Kind.UNLOCK);
        grant(owner,"eternia:path/cobblestone",OwnershipService.Kind.UNLOCK);
        grant(owner,HousingService.MOVE_CREDIT,OwnershipService.Kind.QUANTITY);
        grant(owner,"eternia:service/mailbox",OwnershipService.Kind.UNLOCK);
    }
    @Override public void close() {
        if (bridge!=null) bridge.close();
        if (store instanceof AutoCloseable closeable) try{closeable.close();}catch(Exception e){throw new IllegalStateException("Could not close Eternia data store",e);}
    }
}
