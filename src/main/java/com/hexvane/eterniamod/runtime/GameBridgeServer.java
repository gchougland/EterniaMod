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
    /** Online player count for the public website. Only a number leaves the game: never names or ids. */
    private java.util.function.IntSupplier onlinePlayers=GameBridgeServer::universePlayerCount;
    public void setOnlinePlayers(java.util.function.IntSupplier source){onlinePlayers=Objects.requireNonNull(source);}
    private static int universePlayerCount(){
        var universe=com.hypixel.hytale.server.core.universe.Universe.get();
        return universe==null?-1:universe.getPlayerCount();
    }
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
            if(!exchange.getRequestMethod().equals("GET")&&!exchange.getRequestMethod().equals("HEAD")){
                exchange.getResponseHeaders().set("Allow","GET, HEAD");reply(exchange,405,Map.of("error","Method not allowed"));return;
            }
            String path=exchange.getRequestURI().getPath();
            if(path.equals("/v1/health")){reply(exchange,200,Map.of("status","ready"));return;}
            if(path.equals("/v1/status/players")){
                int online=onlinePlayers.getAsInt();
                if(online<0){reply(exchange,503,Map.of("error","The realm is not ready yet."));return;}
                reply(exchange,200,Map.of("source","game","online",online,"updatedAt",Instant.now()));return;
            }
            if(path.equals("/v1/analytics")){analytics(exchange);return;}
            if(path.equals("/v1/store/offers")){reply(exchange,200,Map.of("source","game","offers",offers));return;}
            if(path.equals("/v1/store/crowns")){
                var products=services.readView(GameBridgeServer.class,java.time.Duration.ofSeconds(10),()->services.commerce().products()).stream()
                    .filter(p->Objects.equals(tebexPackages.get(p.packageId()),p.revision())&&!p.subscription()&&p.benefits().size()==1)
                    .filter(p->{var b=p.benefits().getFirst();return b.contentId().equals(PremiumService.CROWNS)&&b.kind()==OwnershipService.Kind.QUANTITY&&!b.expiresWithSubscription();})
                    .map(p->Map.of("id",p.packageId(),"revision",p.revision(),"crowns",p.benefits().getFirst().quantity())).toList();
                reply(exchange,200,Map.of("source","game","products",products));return;
            }
            if(path.equals("/v1/store/ranks")){
                // Rank packages the game will deliver: one benefit, a ladder step or Founder (one time) or Eternal (leased, subscription).
                var products=services.readView(RankProductsKey.INSTANCE,java.time.Duration.ofSeconds(10),()->services.commerce().products()).stream()
                    .filter(p->Objects.equals(tebexPackages.get(p.packageId()),p.revision())&&p.benefits().size()==1)
                    .map(GameBridgeServer::rankProduct).filter(Objects::nonNull).toList();
                reply(exchange,200,Map.of("source","game","products",products));return;
            }
            if(path.matches("/v1/players/[0-9a-fA-F-]{36}/overview")) {
                UUID player=UUID.fromString(path.split("/")[3]);
                var payload=services.readView(new OverviewKey(player),java.time.Duration.ofSeconds(2),()->overviewPayload(player));
                if(payload==null){reply(exchange,404,Map.of("error","Join Eternia once to create your game profile."));return;}
                reply(exchange,200,payload);return;
            }
            reply(exchange,404,Map.of("error","Not found"));
        } catch(DomainException e){reply(exchange,503,Map.of("error","Authoritative account data is temporarily unavailable."));}
          catch(IllegalArgumentException e){reply(exchange,400,Map.of("error","Invalid request"));}
          catch(RuntimeException e){reply(exchange,500,Map.of("error","Internal server error"));}
        finally {exchange.close();}
    }
    private record OverviewKey(UUID player) {}
    private record AnalyticsKey(java.time.LocalDate from,java.time.LocalDate to) {}
    private final Object analyticsLock=new Object();
    /** One CSV report for the website's admin Analytics page: ?report=daily&from=2026-11-01&to=2026-11-30 (UTC days). */
    private void analytics(HttpExchange exchange)throws IOException {
        var query=new HashMap<String,String>();
        String raw=exchange.getRequestURI().getRawQuery();
        if(raw!=null)for(String part:raw.split("&")){int eq=part.indexOf('=');if(eq>0)query.put(java.net.URLDecoder.decode(part.substring(0,eq),StandardCharsets.UTF_8),java.net.URLDecoder.decode(part.substring(eq+1),StandardCharsets.UTF_8));}
        String report=query.getOrDefault("report","");
        if(!TelemetryReports.NAMES.contains(report)){reply(exchange,400,Map.of("error","Unknown report"));return;}
        java.time.LocalDate from,to;
        try{from=java.time.LocalDate.parse(query.getOrDefault("from",""));to=java.time.LocalDate.parse(query.getOrDefault("to",""));}
        catch(java.time.format.DateTimeParseException e){reply(exchange,400,Map.of("error","Dates must look like 2026-11-30"));return;}
        String csv;
        try{
            // One report at a time: the bridge has few threads and every read takes the database lock.
            synchronized(analyticsLock){
                var input=services.readView(new AnalyticsKey(from,to),java.time.Duration.ofSeconds(30),()->services.telemetry().reportInput(from,to));
                csv=new TelemetryReports(input,from,to,java.time.LocalDate.now(java.time.ZoneOffset.UTC)).report(report);
            }
        }catch(IllegalArgumentException e){reply(exchange,400,Map.of("error",e.getMessage()==null?"Invalid request":e.getMessage()));return;}
        byte[] body=csv.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","text/csv; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        if(exchange.getRequestMethod().equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",Integer.toString(body.length));exchange.sendResponseHeaders(200,-1);return;}
        exchange.sendResponseHeaders(200,body.length);
        try(var out=exchange.getResponseBody()){out.write(body);}
    }
    private String overviewPayload(UUID player){
                var overview=services.overview(player);
                if(overview.account()==null)return null;
                var payload=new LinkedHashMap<String,Object>();
                payload.put("source","game");payload.put("updatedAt",Instant.now());
                var held=services.ranks().ranks(player);var paid=services.ranks().paid(player);
                // Shown rank: the highest supporter rank held by any means. Upgrade prices use only the paid ladder.
                String shown="Citizen";for(String id:com.hexvane.eterniamod.domain.SupporterRanks.ALL)if(held.contains(id))shown=com.hexvane.eterniamod.ranks.Rank.find(id).map(com.hexvane.eterniamod.ranks.Rank::displayName).orElse(shown);
                payload.put("account",Map.of("uuid",player,"name",overview.account().displayName(),"rank",shown));
                payload.put("ranks",Map.of("held",List.copyOf(held),"paid",paid,"ladder",com.hexvane.eterniamod.domain.SupporterRanks.ladderRank(paid)));
                payload.put("coins",overview.coins());
                payload.put("crowns",services.premium().balance(player));
                var collectionNames=new HashMap<String,String>();services.collection().definitions().forEach(d->collectionNames.put(d.id(),d.name()));
                var owned=new TreeMap<String,Map<String,Object>>();
                for(var grant:overview.grants())if(!grant.revoked()&&(grant.validUntil()==null||grant.validUntil().isAfter(Instant.now()))) {
                    var item=owned.computeIfAbsent(grant.contentId(),id->{var value=new LinkedHashMap<String,Object>();value.put("id",id);value.put("name",collectionNames.getOrDefault(id,contentLabel(id)));value.put("kind",grant.kind().name().toLowerCase(Locale.ROOT));value.put("quantity",0L);return value;});
                    item.put("quantity",grant.kind()==OwnershipService.Kind.QUANTITY?Math.addExact((Long)item.get("quantity"),grant.available()):1L);
                }
                for(var entry:com.hexvane.eterniamod.ui.CollectionInventory.summarize(overview.grants(),services.provenance().ownedInstances(Owner.player(player)),Instant.now())) {
                    var item=owned.get(entry.contentId());
                    if(item!=null){item.put("packed",entry.packed());item.put("placed",entry.placed());}
                }
                payload.put("owned",owned.values());
                payload.put("housing",overview.housing()==null?Map.of("state","unclaimed"):overview.housing());
                payload.put("guild",overview.guild());
                payload.put("seasons",services.seasons().progress(player));
                var stats=services.accounts().stats(player).orElse(null);
                if(stats!=null){var measured=new LinkedHashMap<String,Object>();measured.put("playtimeHours",stats.playtimeSeconds()/3600.0);measured.put("mobsDefeated",stats.mobsDefeated());measured.put("resourcesGathered",stats.resourcesGathered());payload.put("stats",measured);}
        return json.toJson(payload);
    }
    private void reply(HttpExchange e,int status,Object value)throws IOException {
        byte[] body=(value instanceof String encoded?encoded:json.toJson(value)).getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");
        e.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        if(e.getRequestMethod().equals("HEAD")){
            e.getResponseHeaders().set("Content-Length",Integer.toString(body.length));
            e.sendResponseHeaders(status,-1);return;
        }
        e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);
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

    private enum RankProductsKey { INSTANCE }

    /** A rank package as the website sees it, or null when the product is not a well formed rank package. */
    static Map<String,Object> rankProduct(com.hexvane.eterniamod.domain.CommerceService.Product p){
        var b=p.benefits().getFirst();
        if(b.kind()!=OwnershipService.Kind.UNLOCK||b.quantity()!=1)return null;
        String content=b.contentId();
        if(content.startsWith(com.hexvane.eterniamod.domain.SupporterRanks.STEP_PREFIX)){
            if(p.subscription()||b.expiresWithSubscription())return null;
            String[] step=content.substring(com.hexvane.eterniamod.domain.SupporterRanks.STEP_PREFIX.length()).split("-");
            try{if(step.length!=2||!com.hexvane.eterniamod.domain.SupporterRanks.step(step[0],step[1]).equals(content))return null;}catch(IllegalArgumentException bad){return null;}
            return Map.of("id",p.packageId(),"revision",p.revision(),"rank",step[1],"from",step[0],"subscription",false);
        }
        if(content.equals(com.hexvane.eterniamod.domain.SupporterRanks.content(com.hexvane.eterniamod.domain.SupporterRanks.FOUNDER))&&!p.subscription()&&!b.expiresWithSubscription())
            return Map.of("id",p.packageId(),"revision",p.revision(),"rank","founder","from","none","subscription",false);
        if(content.equals(com.hexvane.eterniamod.domain.SupporterRanks.content(com.hexvane.eterniamod.domain.SupporterRanks.ETERNAL))&&p.subscription()&&b.expiresWithSubscription())
            return Map.of("id",p.packageId(),"revision",p.revision(),"rank","eternal","from","none","subscription",true);
        return null;
    }
}
