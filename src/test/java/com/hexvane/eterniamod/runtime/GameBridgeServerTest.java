package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.CommerceService;
import com.hexvane.eterniamod.domain.OwnershipService;
import com.hexvane.eterniamod.domain.PremiumService;
import com.google.gson.JsonParser;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameBridgeServerTest {
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
