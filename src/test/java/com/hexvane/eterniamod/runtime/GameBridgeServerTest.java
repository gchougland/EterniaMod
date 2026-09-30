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
}
