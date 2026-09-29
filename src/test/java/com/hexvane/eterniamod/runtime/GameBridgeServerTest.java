package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameBridgeServerTest {
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
