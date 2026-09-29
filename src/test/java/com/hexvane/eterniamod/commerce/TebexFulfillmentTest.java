package com.hexvane.eterniamod.commerce;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TebexFulfillmentTest {
    private final byte[] secret="fixture-only-webhook-secret".getBytes(StandardCharsets.UTF_8);
    @Test void signatureUsesRawBodyHexDigestAndRejectsModification()throws Exception{
        byte[] body="{\"id\":\"one\"}".getBytes(StandardCharsets.UTF_8);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));String signature=HexFormat.of().formatHex(mac.doFinal(digest.getBytes(StandardCharsets.US_ASCII)));
        assertTrue(TebexFulfillment.verifySignature(body,signature,secret));assertFalse(TebexFulfillment.verifySignature("{ \"id\":\"one\"}".getBytes(StandardCharsets.UTF_8),signature,secret));assertFalse(TebexFulfillment.verifySignature(body,"bad",secret));assertFalse(TebexFulfillment.verifySignature(body,null,secret));
    }
    @Test void signedPaymentUsesProductRecipientAndAuthoritativeQuantity(){
        var services=new EterniaServices(new InMemoryStore());UUID player=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(player,"Mira");services.commerce().register(new CommerceService.Product("123",1,"Chair",List.of(new CommerceService.Benefit("eternia:chair",OwnershipService.Kind.QUANTITY,1,false)),false));
        var adapter=new TebexFulfillment(services,Map.of("123",1),new String(secret,StandardCharsets.UTF_8),Runnable::run);
        String json="""
            {"id":"event-one","type":"payment.completed","subject":{"transaction_id":"tbx-one","status":{"id":1},"created_at":"2026-09-12T10:00:00Z","products":[{"id":123,"quantity":2,"username":{"id":"%s","username":"Ignored"},"expires_at":null}],"recurring_payment_reference":null}}
            """.formatted(player);
        adapter.acceptVerifiedBody(json.getBytes(StandardCharsets.UTF_8));assertEquals(0,services.ownership().available(Owner.player(player),"eternia:chair"));
        services.commerceIngress().expectConsole("tbx-one","123",player,2,1);adapter.reconcile();adapter.acceptVerifiedBody(json.getBytes(StandardCharsets.UTF_8));assertEquals(2,services.ownership().available(Owner.player(player),"eternia:chair"));
    }
    @Test void platformNumericIdentifierCannotBeMistakenForGameUuid(){assertThrows(IllegalArgumentException.class,()->TebexFulfillment.gameUuid("1234"));UUID id=UUID.randomUUID();assertEquals(id,TebexFulfillment.gameUuid(id.toString().replace("-","")));}
    @Test void duplicatePackagePayloadCannotStageItsFirstLineBeforeRejection(){
        var services=new EterniaServices(new InMemoryStore());UUID player=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(player,"Mira");services.commerce().register(new CommerceService.Product("123",1,"Chair",List.of(new CommerceService.Benefit("eternia:chair",OwnershipService.Kind.QUANTITY,1,false)),false));
        var adapter=new TebexFulfillment(services,Map.of("123",1),new String(secret,StandardCharsets.UTF_8),Runnable::run);services.commerceIngress().expectConsole("tbx-duplicate","123",player,1,1);
        String line="{\"id\":123,\"quantity\":1,\"username\":{\"id\":\""+player+"\"}}";
        String body="{\"id\":\"event\",\"type\":\"payment.completed\",\"subject\":{\"transaction_id\":\"tbx-duplicate\",\"status\":{\"id\":1},\"created_at\":\"2026-09-12T10:00:00Z\",\"products\":["+line+","+line+"]}}";
        assertThrows(IllegalArgumentException.class,()->adapter.acceptVerifiedBody(body.getBytes(StandardCharsets.UTF_8)));adapter.reconcile();assertEquals(0,services.ownership().available(Owner.player(player),"eternia:chair"));
    }
}
