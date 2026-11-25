package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.CommerceService;
import com.hexvane.eterniamod.domain.OwnershipService;
import com.hexvane.eterniamod.domain.PremiumService;
import com.hexvane.eterniamod.commerce.TebexFulfillment;
import com.google.gson.JsonParser;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameBridgeServerTest {
    @Test void headRequestsKeepAuthenticationAndReturnNoBodyWithoutHttpServerWarnings() throws Exception {
        String token="test-service-token-12345678901234567890";
        var config=new RuntimeConfig(true,"","","","127.0.0.1",0,token,"http://127.0.0.1:3000");
        var services=new EterniaServices(new InMemoryStore());
        var tebex=new TebexFulfillment(services,java.util.Map.of(),"local-test-webhook-secret",Runnable::run);
        var warnings=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var logger=java.util.logging.Logger.getLogger("com.sun.net.httpserver");
        var handler=new java.util.logging.Handler(){
            @Override public void publish(java.util.logging.LogRecord record){if(record.getLevel().intValue()>=java.util.logging.Level.WARNING.intValue())warnings.add(record.getMessage());}
            @Override public void flush(){}
            @Override public void close(){}
        };
        logger.addHandler(handler);
        try(var bridge=new GameBridgeServer(services,config);var client=HttpClient.newHttpClient()){
            bridge.mount("/v1/commerce/tebex",tebex.webhookHandler());bridge.start();
            URI root=URI.create("http://127.0.0.1:"+bridge.port());
            var paths=java.util.Map.of("/v1/health",200,"/v1/store/crowns",200,"/missing",404,"/v1/commerce/tebex",405,"/v1/commerce/tebex/extra",404);
            for(var entry:paths.entrySet())for(boolean authenticated:new boolean[]{false,true}){
                var request=HttpRequest.newBuilder(root.resolve(entry.getKey())).method("HEAD",HttpRequest.BodyPublishers.noBody());
                if(authenticated)request.header("Authorization","Bearer "+token);
                var response=client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
                int expected=authenticated||entry.getKey().endsWith("/extra")?entry.getValue():401;
                assertEquals(expected,response.statusCode(),entry.getKey());assertEquals(0,response.body().length);
                assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
                if(authenticated&&entry.getValue()==405)assertEquals("POST",response.headers().firstValue("Allow").orElseThrow());
            }
            var get=client.send(HttpRequest.newBuilder(root.resolve("/v1/health")).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,get.statusCode());assertEquals("ready",JsonParser.parseString(get.body()).getAsJsonObject().get("status").getAsString());
            assertTrue(warnings.isEmpty(),warnings.toString());
        }finally{logger.removeHandler(handler);}
    }
    @Test void onlinePlayerCountIsPrivateNumberOnlyAndUnavailableBeforeTheRealmIsReady() throws Exception {
        String token="test-service-token-12345678901234567890";
        var config=new RuntimeConfig(true,"","","","127.0.0.1",0,token,"http://127.0.0.1:3000");
        try(var bridge=new GameBridgeServer(new EterniaServices(new InMemoryStore()),config);var client=HttpClient.newHttpClient()){
            var online=new java.util.concurrent.atomic.AtomicInteger(-1);bridge.setOnlinePlayers(online::get);bridge.start();
            var uri=URI.create("http://127.0.0.1:"+bridge.port()+"/v1/status/players");
            assertEquals(401,client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var request=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+token).build();
            assertEquals(503,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
            online.set(42);
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
            var body=JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(java.util.Set.of("source","online","updatedAt"),body.keySet());
            assertEquals("game",body.get("source").getAsString());assertEquals(42,body.get("online").getAsInt());
            assertEquals(405,client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void analyticsServesCsvReportsOnlyWithTheToken() throws Exception {
        String token="t".repeat(40);
        var config=new RuntimeConfig(true,"","","","127.0.0.1",0,token,"http://127.0.0.1:3000");
        var services=new EterniaServices(new InMemoryStore());var player=java.util.UUID.randomUUID();
        services.welcomePlayer(player,"Aria");services.telemetry().sample(java.util.List.of(player));
        try(var bridge=new GameBridgeServer(services,config);var client=HttpClient.newHttpClient()){
            bridge.start();
            String today=java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
            var uri=URI.create("http://127.0.0.1:"+bridge.port()+"/v1/analytics?report=daily&from="+today+"&to="+today);
            assertEquals(401,client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var response=client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
            var lines=response.body().lines().toList();
            assertTrue(lines.get(0).startsWith("period_start,period_end,unique_players"));
            assertTrue(lines.get(1).startsWith(today+","+today+",1,1,0,1,1.00,1"),lines.get(1));
            var bad=URI.create("http://127.0.0.1:"+bridge.port()+"/v1/analytics?report=passwords&from="+today+"&to="+today);
            assertEquals(400,client.send(HttpRequest.newBuilder(bad).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void crownCatalogContainsOnlyActiveMappedPermanentCurrencyProducts() throws Exception {
        String token="test-service-token-12345678901234567890";
        var config=new RuntimeConfig(true,"","","","127.0.0.1",0,token,"http://127.0.0.1:3000");
        var services=new EterniaServices(new InMemoryStore());
        var benefit=new CommerceService.Benefit(PremiumService.CROWNS,OwnershipService.Kind.QUANTITY,500,false);
        services.commerce().register(new CommerceService.Product("7704919",1,"Pouch",java.util.List.of(benefit),false));
        services.commerce().register(new CommerceService.Product("7704919",2,"Pouch revised",java.util.List.of(new CommerceService.Benefit(PremiumService.CROWNS,OwnershipService.Kind.QUANTITY,600,false)),false));
        services.commerce().register(new CommerceService.Product("7704922",1,"Subscription",java.util.List.of(benefit),true));
        services.commerce().register(new CommerceService.Product("7704924",1,"Mixed",java.util.List.of(benefit,new CommerceService.Benefit("eternia:prop/chair",OwnershipService.Kind.QUANTITY,1,false)),false));
        try(var bridge=new GameBridgeServer(services,config);var client=HttpClient.newHttpClient()){
            bridge.start();var uri=URI.create("http://127.0.0.1:"+bridge.port()+"/v1/store/crowns");
            assertEquals(401,client.send(HttpRequest.newBuilder(uri).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var request=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+token).build();
            assertTrue(JsonParser.parseString(client.send(request,HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject().getAsJsonArray("products").isEmpty());
            bridge.setTebexPackages(java.util.Map.of("7704919",1,"7704922",1,"7704924",1));
            var products=JsonParser.parseString(client.send(request,HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject().getAsJsonArray("products");
            assertEquals(1,products.size());var product=products.get(0).getAsJsonObject();assertEquals("7704919",product.get("id").getAsString());assertEquals(1,product.get("revision").getAsInt());assertEquals(500,product.get("crowns").getAsLong());
        }
    }
    @Test void privateRoutesAndMountedWebhooksRequireBearerAndExactPath() throws Exception {
        String token="test-service-token-12345678901234567890";
        var config=new RuntimeConfig(true,"","","","127.0.0.1",0,token,"http://127.0.0.1:3000");
        try(var bridge=new GameBridgeServer(new EterniaServices(new InMemoryStore()),config);var client=HttpClient.newHttpClient()){
            bridge.mount("/v1/commerce/tebex",e->{byte[] body=e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});bridge.start();
            URI root=URI.create("http://127.0.0.1:"+bridge.port());
            assertEquals(401,client.send(HttpRequest.newBuilder(root.resolve("/v1/health")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            byte[] raw="{ \"unaltered\": true }\n".getBytes(StandardCharsets.UTF_8);
            var request=HttpRequest.newBuilder(root.resolve("/v1/commerce/tebex")).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode());assertArrayEquals(raw,response.body());
            assertEquals(404,client.send(HttpRequest.newBuilder(root.resolve("/v1/commerce/tebex/extra")).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404,client.send(HttpRequest.newBuilder(root.resolve("/v1/players/"+UUID.randomUUID()+"/overview")).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void rankCatalogListsWellFormedRankPackagesAndTheOverviewShowsPaidRanks() throws Exception {
        String token="test-service-token-12345678901234567890";
        var config=new RuntimeConfig(true,"","","","127.0.0.1",0,token,"http://127.0.0.1:3000");
        var services=new EterniaServices(new InMemoryStore());
        java.util.function.BiConsumer<String,CommerceService.Benefit> one=(id,b)->services.commerce().register(new CommerceService.Product(id,1,id,java.util.List.of(b),b.expiresWithSubscription()));
        one.accept("1",new CommerceService.Benefit("eternia:rank_step/none-starborn",OwnershipService.Kind.UNLOCK,1,false));
        one.accept("2",new CommerceService.Benefit("eternia:rank_step/starborn-celestial",OwnershipService.Kind.UNLOCK,1,false));
        one.accept("3",new CommerceService.Benefit("eternia:rank/eternal",OwnershipService.Kind.UNLOCK,1,true));
        one.accept("4",new CommerceService.Benefit("eternia:rank_step/celestial-starborn",OwnershipService.Kind.UNLOCK,1,false));
        services.commerce().register(new CommerceService.Product("5",1,"Eternal one off",java.util.List.of(new CommerceService.Benefit("eternia:rank/eternal",OwnershipService.Kind.UNLOCK,1,false)),false));
        UUID player=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(player,"Supporter");
        services.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex","pay","line","1",1,com.hexvane.eterniamod.domain.Owner.player(player),1,"",null,null));
        try(var bridge=new GameBridgeServer(services,config);var client=HttpClient.newHttpClient()){
            bridge.start();bridge.setTebexPackages(java.util.Map.of("1",1,"2",1,"3",1,"4",1,"5",1));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+bridge.port()+"/v1/store/ranks")).header("Authorization","Bearer "+token).build();
            var products=JsonParser.parseString(client.send(request,HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject().getAsJsonArray("products");
            var ids=new java.util.TreeSet<String>();for(var p:products)ids.add(p.getAsJsonObject().get("id").getAsString());
            assertEquals(java.util.Set.of("1","2","3"),ids,"a downward step and a one off Eternal are not rank packages");
            for(var p:products){var o=p.getAsJsonObject();if(o.get("id").getAsString().equals("2")){assertEquals("celestial",o.get("rank").getAsString());assertEquals("starborn",o.get("from").getAsString());}
                if(o.get("id").getAsString().equals("3"))assertTrue(o.get("subscription").getAsBoolean());}
            var overview=JsonParser.parseString(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+bridge.port()+"/v1/players/"+player+"/overview")).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
            assertEquals("starborn",overview.getAsJsonObject("ranks").get("ladder").getAsString());
            assertEquals("Settler",overview.getAsJsonObject("account").get("rank").getAsString());
        }
    }
}
