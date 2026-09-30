package com.hexvane.eterniamod.runtime;

import com.google.gson.*;
import com.hexvane.eterniamod.domain.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Private service-to-service read API. Browser credentials and arbitrary identity headers are never accepted. */
public final class GameBridgeServer implements AutoCloseable {
    private final HttpServer server;private final ExecutorService executor;private final EterniaServices services;private final byte[] token;
    private final Gson json=new GsonBuilder().registerTypeAdapter(Instant.class,(JsonSerializer<Instant>)(value,type,ctx)->new JsonPrimitive(value.toString())).create();
    private List<StoreOffers.Offer> offers=List.of();
    public void setOffers(List<StoreOffers.Offer> values){offers=List.copyOf(values);}
    private Map<String,Integer> tebexPackages=Map.of();
    public void setTebexPackages(Map<String,Integer> values){tebexPackages=Map.copyOf(values);}
    public GameBridgeServer(EterniaServices services,RuntimeConfig config)throws IOException {
        this.services=services;token=("Bearer "+config.bridgeToken()).getBytes(StandardCharsets.UTF_8);
        if(config.bridgeToken().length()<32)throw new IllegalArgumentException("Bridge requires a strong service token");
        server=HttpServer.create(new InetSocketAddress(config.bridgeAddress(),config.bridgePort()),16);
        executor=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),Thread.ofPlatform().name("eternia-bridge-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public void mount(String path,HttpHandler handler){
        if(!path.startsWith("/v1/"))throw new IllegalArgumentException("Bridge routes must use /v1/");
        server.createContext(path,exchange->{
            String auth=exchange.getRequestHeaders().getFirst("Authorization");
            if(!exchange.getRequestURI().getPath().equals(path)){reply(exchange,404,Map.of("error","Not found"));exchange.close();return;}
            if(auth==null||auth.length()>1024||!MessageDigest.isEqual(token,auth.getBytes(StandardCharsets.UTF_8))){reply(exchange,401,Map.of("error","Unauthorized"));exchange.close();return;}
            handler.handle(exchange);
        });
    }
    public int port(){return server.getAddress().getPort();}
    private void handle(HttpExchange exchange)throws IOException {
        try {
            String auth=exchange.getRequestHeaders().getFirst("Authorization");
            if(auth==null||auth.length()>1024||!MessageDigest.isEqual(token,auth.getBytes(StandardCharsets.UTF_8))){reply(exchange,401,Map.of("error","Unauthorized"));return;}
            if(!exchange.getRequestMethod().equals("GET")){reply(exchange,405,Map.of("error","Method not allowed"));return;}
            String path=exchange.getRequestURI().getPath();
            if(path.equals("/v1/health")){reply(exchange,200,Map.of("status","ready"));return;}
            if(path.equals("/v1/store/offers")){reply(exchange,200,Map.of("source","game","offers",offers));return;}
            if(path.equals("/v1/store/crowns")){
                var products=services.commerce().products().stream()
                    .filter(p->Objects.equals(tebexPackages.get(p.packageId()),p.revision())&&!p.subscription()&&p.benefits().size()==1)
                    .filter(p->{var b=p.benefits().getFirst();return b.contentId().equals(PremiumService.CROWNS)&&b.kind()==OwnershipService.Kind.QUANTITY&&!b.expiresWithSubscription();})
                    .map(p->Map.of("id",p.packageId(),"revision",p.revision(),"crowns",p.benefits().getFirst().quantity())).toList();
                reply(exchange,200,Map.of("source","game","products",products));return;
            }
            if(path.matches("/v1/players/[0-9a-fA-F-]{36}/overview")) {
                UUID player=UUID.fromString(path.split("/")[3]);
                var overview=services.overview(player);
                if(overview.account()==null){reply(exchange,404,Map.of("error","Join Eternia once to create your game profile."));return;}
                var payload=new LinkedHashMap<String,Object>();
                payload.put("source","game");payload.put("updatedAt",Instant.now());
                payload.put("account",Map.of("uuid",player,"name",overview.account().displayName(),"rank",services.ownership().owns(Owner.player(player),"eternia:rank/supporter")?"Supporter":"Citizen"));
                payload.put("coins",overview.coins());
                payload.put("crowns",services.premium().balance(player));
                var collectionNames=new HashMap<String,String>();services.collection().definitions().forEach(d->collectionNames.put(d.id(),d.name()));
                var owned=new TreeMap<String,Map<String,Object>>();
                for(var grant:overview.grants())if(!grant.revoked()&&(grant.validUntil()==null||grant.validUntil().isAfter(Instant.now()))) {
                    var item=owned.computeIfAbsent(grant.contentId(),id->{var value=new LinkedHashMap<String,Object>();value.put("id",id);value.put("name",collectionNames.getOrDefault(id,contentLabel(id)));value.put("kind",grant.kind().name().toLowerCase(Locale.ROOT));value.put("quantity",0L);return value;});
                    item.put("quantity",grant.kind()==OwnershipService.Kind.QUANTITY?Math.addExact((Long)item.get("quantity"),grant.available()):1L);
                }
                payload.put("owned",owned.values());
                payload.put("housing",overview.housing()==null?Map.of("state","unclaimed"):overview.housing());
                payload.put("guild",overview.guild());
                payload.put("seasons",services.seasons().progress(player));
                var stats=services.accounts().stats(player).orElse(null);
                if(stats!=null){var measured=new LinkedHashMap<String,Object>();measured.put("playtimeHours",stats.playtimeSeconds()/3600.0);measured.put("mobsDefeated",stats.mobsDefeated());measured.put("resourcesGathered",stats.resourcesGathered());payload.put("stats",measured);}
                reply(exchange,200,payload);return;
            }
            reply(exchange,404,Map.of("error","Not found"));
        } catch(DomainException e){reply(exchange,503,Map.of("error","Authoritative account data is temporarily unavailable."));}
          catch(IllegalArgumentException e){reply(exchange,400,Map.of("error","Invalid request"));}
          catch(RuntimeException e){reply(exchange,500,Map.of("error","Internal server error"));}
        finally {exchange.close();}
    }
    private void reply(HttpExchange e,int status,Object value)throws IOException {
        byte[] body=json.toJson(value).getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");
        e.getResponseHeaders().set("X-Content-Type-Options","nosniff");e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);
    }
    private static String contentLabel(String id) {
        return switch(id){
            case "eternia:plot/free" -> "24 × 24 housing plot";
            case "eternia:plot/personal_32" -> "32 × 32 housing plot";
            case "eternia:plot_move_credit" -> "Plot move credit";
            default -> {
                String stem=id.substring(Math.max(id.lastIndexOf('/'),id.lastIndexOf(':'))+1).replace('_',' ').replace('-',' ');
                yield stem.isEmpty()?"Collection item":Character.toUpperCase(stem.charAt(0))+stem.substring(1);
            }
        };
    }
    @Override public void close(){server.stop(0);executor.shutdownNow();}
}
