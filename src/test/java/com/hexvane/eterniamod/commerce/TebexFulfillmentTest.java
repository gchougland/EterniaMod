package com.hexvane.eterniamod.commerce;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    private final class CrownFixture {
        final InMemoryStore store=new InMemoryStore();
        final EterniaServices services=new EterniaServices(store);
        final UUID player=UUID.randomUUID(),provider=UUID.randomUUID();
        final TebexFulfillment adapter;
        CrownFixture(){
            services.accounts().recordAuthenticatedLogin(player,"Mira");
            services.commerce().register(new CommerceService.Product("123",1,"Crowns",List.of(new CommerceService.Benefit(PremiumService.CROWNS,OwnershipService.Kind.QUANTITY,500,false)),false));
            services.premium().creditLocalExample(player,300,"local-playground:initial");
            adapter=new TebexFulfillment(services,Map.of("123",1),new String(secret,StandardCharsets.UTF_8),Runnable::run);
        }
        byte[] body(){return body(provider.toString().replace("-",""),player.toString());}
        byte[] body(String providerId,String profile){return """
            {"id":"fixture-event","type":"payment.completed","subject":{"transaction_id":"tbx-fixture","status":{"id":1},"created_at":"2026-10-29T17:52:20Z",
            "customer":{"username":{"id":"%s","username":"Mira"}},"products":[{"id":123,"type":"single","quantity":1,"username":{"id":"%s","username":"Mira"}}],
            "custom":{"eternia_profile_uuid":"%s"}}}
            """.formatted(providerId,providerId,profile).getBytes(StandardCharsets.UTF_8);}
        CommerceService.VerifiedPurchase legacy(){return new CommerceService.VerifiedPurchase("tebex","tbx-fixture","123","123",1,Owner.player(provider),1,"",null,null);}
        void stageLegacy(){services.commerceIngress().stageVerifiedLine(legacy());}
        void console(){services.commerceIngress().expectConsole("tbx-fixture","123",player,1,1);}
        long crowns(){return services.premium().balance(player).available();}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void websiteReceiptUsesSignedProfileInEitherArrivalOrder(boolean consoleFirst){
        var f=new CrownFixture();
        if(consoleFirst)f.console();
        f.adapter.acceptVerifiedBody(f.body());
        if(!consoleFirst){assertEquals(300,f.crowns());f.console();}
        f.adapter.reconcile();f.adapter.acceptVerifiedBody(f.body());f.adapter.reconcile();
        assertEquals(800,f.crowns());
        assertEquals(1,f.store.transaction(tx->tx.scan("commerce_purchase").size()).intValue());
    }
    @Test void numericProviderIdWorksWhenSignedProfileAndConsoleAgree(){
        var f=new CrownFixture();f.console();f.adapter.acceptVerifiedBody(f.body("987654",f.player.toString()));
        assertEquals(800,f.crowns());
    }
    @Test void replayRepairsLegacyReceiptAfterRestartAndCreditsOnlyOnce()throws Exception{
        var f=new CrownFixture();f.stageLegacy();f.console();
        assertEquals(0,f.services.commerceIngress().reconcile(25));assertEquals(300,f.crowns());
        var restarted=new TebexFulfillment(new EterniaServices(f.store),Map.of("123",1),new String(secret,StandardCharsets.UTF_8),Runnable::run);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var jobs=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<4;i++)jobs.add(pool.submit(()->{restarted.acceptVerifiedBody(f.body());restarted.reconcile();}));
            for(var job:jobs)job.get();
        }
        assertEquals(800,f.crowns());
        var repairs=f.store.transaction(tx->tx.scan("tebex_identity_repair"));assertEquals(1,repairs.size());
        assertEquals(Owner.player(f.provider).key(),repairs.getFirst().value("previousRecipient"));
        assertEquals(Owner.player(f.player).key(),repairs.getFirst().value("recipient"));
        restarted.acceptVerifiedBody("""
            {"id":"refund","type":"payment.refunded","subject":{"transaction_id":"tbx-fixture"}}
            """.getBytes(StandardCharsets.UTF_8));
        restarted.acceptVerifiedBody(f.body());assertEquals(300,f.crowns());
    }
    @ParameterizedTest @ValueSource(strings={"missing-console","wrong-console","quantity","revision","provider","refund","started","known-account","review","done"})
    void legacyRepairRefusesUnprovenOrPreviouslyProcessedPurchases(String blocker){
        var f=new CrownFixture();f.stageLegacy();
        if(blocker.equals("wrong-console"))f.services.commerceIngress().expectConsole("tbx-fixture","123",UUID.randomUUID(),1,1);
        else if(blocker.equals("revision"))f.services.commerceIngress().expectConsole("tbx-fixture","123",f.player,1,2);
        else if(!blocker.equals("missing-console"))f.console();
        if(blocker.equals("refund"))f.services.commerceIngress().reverseTransaction("tbx-fixture","payment.refunded");
        if(blocker.equals("started"))f.services.commerce().fulfill(f.legacy());
        if(blocker.equals("known-account"))f.services.accounts().recordAuthenticatedLogin(f.provider,"Other");
        if(blocker.equals("review")||blocker.equals("done"))f.store.transaction(tx->{
            var row=tx.scan("tebex_verified").getFirst();
            tx.save(blocker.equals("review")?"tebex_review":"tebex_done",row.key(),0,Map.of("reason","CONSOLE_MISMATCH"));return null;});
        byte[] body=f.body();
        if(blocker.equals("quantity"))body=new String(body,StandardCharsets.UTF_8).replace("\"quantity\":1","\"quantity\":2").getBytes(StandardCharsets.UTF_8);
        if(blocker.equals("provider"))body=f.body(UUID.randomUUID().toString(),f.player.toString());
        byte[] replay=body;
        assertThrows(DomainException.class,()->f.adapter.acceptVerifiedBody(replay));
        assertEquals(300,f.crowns());
        assertTrue(f.store.transaction(tx->tx.scan("tebex_identity_repair").isEmpty()).booleanValue());
        assertEquals(Owner.player(f.provider).key(),f.store.transaction(tx->tx.scan("tebex_verified").getFirst().value("recipient")));
    }
    @ParameterizedTest @ValueSource(strings={"bad-profile","null-profile","gift","missing-buyer"})
    void invalidSignedIdentityNeverStagesReceipt(String invalid){
        var f=new CrownFixture();f.console();
        var event=JsonParser.parseString(new String(f.body(),StandardCharsets.UTF_8)).getAsJsonObject();
        var payment=event.getAsJsonObject("subject");
        switch(invalid){
            case "bad-profile" -> payment.getAsJsonObject("custom").addProperty("eternia_profile_uuid","not-a-uuid");
            case "null-profile" -> payment.getAsJsonObject("custom").add("eternia_profile_uuid",com.google.gson.JsonNull.INSTANCE);
            case "gift" -> payment.getAsJsonArray("products").get(0).getAsJsonObject().getAsJsonObject("username").addProperty("id",UUID.randomUUID().toString());
            case "missing-buyer" -> payment.remove("customer");
        }
        assertThrows(IllegalArgumentException.class,()->f.adapter.acceptVerifiedBody(event.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(300,f.crowns());assertTrue(f.store.transaction(tx->tx.scan("tebex_verified").isEmpty()).booleanValue());
    }
    @Test void wrongSignedProfileCannotGrantToEitherAccount(){
        var f=new CrownFixture();UUID other=UUID.randomUUID();f.services.accounts().recordAuthenticatedLogin(other,"Other");f.console();
        f.adapter.acceptVerifiedBody(f.body(f.provider.toString(),other.toString()));f.adapter.reconcile();
        assertEquals(300,f.crowns());assertEquals(0,f.services.premium().balance(other).available());
        assertEquals("CONSOLE_MISMATCH",f.services.commerceIngress().reviews().getFirst().reason());
    }
}
