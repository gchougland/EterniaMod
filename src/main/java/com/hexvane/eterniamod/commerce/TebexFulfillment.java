package com.hexvane.eterniamod.commerce;

import com.google.gson.*;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.sun.net.httpserver.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;

/** Tebex's console command and signed payment must agree before the durable service grants anything. */
public final class TebexFulfillment {
    private static final int MAX_BODY=1024*1024;
    private final EterniaServices services;
    private final Map<String,Integer> mappings;
    private final byte[] secret;
    private final Executor executor;
    public TebexFulfillment(EterniaServices services,Map<String,Integer> packageRevisionAllowlist,String webhookSecret,Executor executor){
        this.services=Objects.requireNonNull(services);this.executor=Objects.requireNonNull(executor);
        if(webhookSecret==null||webhookSecret.length()<16)throw new IllegalArgumentException("A Tebex webhook secret is required");
        this.secret=webhookSecret.getBytes(StandardCharsets.UTF_8);this.mappings=Map.copyOf(packageRevisionAllowlist);
        var products=services.commerce().products();
        mappings.forEach((id,revision)->{if(!id.matches("[0-9]{1,20}")||revision<1)throw new IllegalArgumentException("Invalid Tebex package mapping");
            if(products.stream().noneMatch(p->p.packageId().equals(id)&&p.revision()==revision))throw new IllegalArgumentException("Mapped Tebex product revision is not registered: "+id);});
    }
    public void registerConsole(EterniaModPlugin plugin){plugin.getCommandRegistry().registerCommand(new DeliveryCommand());}
    public HttpHandler webhookHandler(){return this::handle;}
    public Map<String,Integer> packageRevisions(){return mappings;}
    public int reconcile(){return services.commerceIngress().reconcile(100);}
    private void handle(HttpExchange exchange)throws IOException{
        try{
            if(!exchange.getRequestMethod().equals("POST")){respond(exchange,405,"{}");return;}
            byte[] body=exchange.getRequestBody().readNBytes(MAX_BODY+1);
            if(body.length>MAX_BODY){respond(exchange,413,"{}");return;}
            if(!verifySignature(body,exchange.getRequestHeaders().getFirst("X-Signature"),secret)){respond(exchange,401,"{}");return;}
            String result=acceptVerifiedBody(body);respond(exchange,200,result);
        }catch(DomainException e){respond(exchange,e.code()==DomainException.Code.STORAGE_UNAVAILABLE?503:409,"{\"status\":\"requires_review\"}");}
        catch(IllegalArgumentException|IllegalStateException e){respond(exchange,422,"{\"status\":\"invalid_provider_payload\"}");}
        finally{exchange.close();}
    }
    /** Package-private after-signature parser, independently exercised by fixtures. No browser data enters here. */
    String acceptVerifiedBody(byte[] body){
        JsonObject event=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
        String id=required(event,"id"),type=required(event,"type");
        if(type.equals("validation.webhook")){var response=new JsonObject();response.addProperty("id",id);return response.toString();}
        JsonObject subject=event.getAsJsonObject("subject");if(subject==null)throw new IllegalArgumentException("Missing subject");
        switch(type){
            case "payment.completed" -> stagePayment(subject,null,null);
            case "recurring-payment.started","recurring-payment.renewed" -> {
                String reference=required(subject,"reference");Instant next=Instant.parse(required(subject,"next_payment_at"));
                JsonObject payment=subject.getAsJsonObject("last_payment");if(payment==null)payment=subject.getAsJsonObject("initial_payment");
                if(payment==null)throw new IllegalArgumentException("Missing recurring payment");stagePayment(payment,reference,next);
            }
            case "payment.refunded","payment.dispute.opened","payment.dispute.lost" -> services.commerceIngress().reverseTransaction(required(subject,"transaction_id"),type);
            case "recurring-payment.cancellation.requested","recurring-payment.cancellation.aborted","recurring-payment.ended" -> {
                String state=type.endsWith(".requested")?"CANCEL_REQUESTED":type.endsWith(".aborted")?"CANCEL_ABORTED":"ENDED";
                services.commerceIngress().stageSubscriptionControl(required(subject,"reference"),state,Instant.parse(required(event,"date")),id);
            }
            // A dispute win/close needs reviewed restoration: it must not mint a second copy of permanent gifts.
            default -> { return "{\"status\":\"ignored\"}"; }
        }
        reconcile();return "{\"status\":\"recorded\"}";
    }
    private void stagePayment(JsonObject payment,String recurringReference,Instant nextPayment){
        JsonObject status=payment.getAsJsonObject("status");if(status==null||status.get("id").getAsInt()!=1)throw new IllegalArgumentException("Payment is not complete");
        String transaction=required(payment,"transaction_id");Instant created=Instant.parse(required(payment,"created_at"));
        String subscription=recurringReference!=null?recurringReference:optional(payment,"recurring_payment_reference");
        JsonArray products=payment.getAsJsonArray("products");if(products==null||products.size()>100)throw new IllegalArgumentException("Invalid products");
        var seen=new HashSet<String>();
        var verifiedLines=new ArrayList<CommerceService.VerifiedPurchase>();
        for(JsonElement element:products){
            var product=element.getAsJsonObject();String packageId=required(product,"id");Integer revision=mappings.get(packageId);if(revision==null)continue;
            if(!seen.add(packageId))throw new IllegalArgumentException("Duplicate package lines require review");
            JsonObject username=product.getAsJsonObject("username");if(username==null)throw new IllegalArgumentException("Missing product recipient");
            UUID recipient=gameUuid(required(username,"id"));long quantity=product.get("quantity").getAsBigDecimal().longValueExact();
            String expiry=optional(product,"expires_at");Instant end=!expiry.isEmpty()?Instant.parse(expiry):nextPayment;
            boolean recurring=services.commerce().products().stream().filter(p->p.packageId().equals(packageId)&&p.revision()==revision).findFirst().map(CommerceService.Product::subscription).orElse(false);
            if(recurring&&(subscription.isEmpty()||end==null))throw new IllegalArgumentException("Missing verified paid subscription period");
            verifiedLines.add(new CommerceService.VerifiedPurchase("tebex",transaction,packageId,packageId,revision,Owner.player(recipient),quantity,recurring?subscription:"",recurring?created:null,recurring?end:null));
        }
        services.commerceIngress().stageVerifiedLines(verifiedLines);
    }
    static boolean verifySignature(byte[] body,String signature,byte[] secret){
        if(signature==null||!signature.matches("[0-9a-fA-F]{64}"))return false;
        try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return MessageDigest.isEqual(mac.doFinal(hash.getBytes(StandardCharsets.US_ASCII)),HexFormat.of().parseHex(signature));}
        catch(GeneralSecurityException e){throw new IllegalStateException("SHA256 HMAC unavailable",e);}
    }
    static UUID gameUuid(String value){
        if(value.matches("[0-9a-fA-F]{32}"))value=value.substring(0,8)+"-"+value.substring(8,12)+"-"+value.substring(12,16)+"-"+value.substring(16,20)+"-"+value.substring(20);
        if(!value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("Recipient is not a game UUID");return UUID.fromString(value);
    }
    private static String required(JsonObject object,String name){String value=optional(object,name);if(value.isBlank()||value.length()>200)throw new IllegalArgumentException("Invalid "+name);return value;}
    private static String optional(JsonObject object,String name){var value=object.get(name);return value==null||value.isJsonNull()?"":value.getAsString();}
    private static void respond(HttpExchange exchange,int status,String json)throws IOException{byte[] bytes=json.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
    private final class DeliveryCommand extends CommandBase{
        private final RequiredArg<String> transaction=withRequiredArg("transaction","Tebex transaction",ArgTypes.STRING);
        private final RequiredArg<String> packageId=withRequiredArg("package","Tebex package",ArgTypes.STRING);
        private final RequiredArg<String> recipient=withRequiredArg("recipient","Tebex game identity",ArgTypes.STRING);
        private final RequiredArg<Integer> quantity=withRequiredArg("quantity","Tebex purchase quantity",ArgTypes.INTEGER);
        DeliveryCommand(){super("eternia-tebex","Verify an Eternia Tebex delivery");}
        @Override protected void executeSync(CommandContext context){
            if(!(context.sender() instanceof ConsoleSender)){context.sendMessage(Message.raw("This command is available only to the server console."));return;}
            String tx=transaction.get(context),pkg=packageId.get(context),who=recipient.get(context);int qty=quantity.get(context);Integer revision=mappings.get(pkg);
            if(revision==null){context.sendMessage(Message.raw("Tebex package is not mapped; no items granted."));return;}
            try{
                // Persist before returning: Tebex considers a successfully dispatched console command consumed.
                services.commerceIngress().expectConsole(tx,pkg,gameUuid(who),qty,revision);
                executor.execute(()->{try{reconcile();}catch(RuntimeException ignored){/* Durable halves remain available to the scheduled reconciler. */}});
                context.sendMessage(Message.raw("Tebex command recorded; matching signed receipt is required for delivery."));
            }catch(RuntimeException e){context.sendMessage(Message.raw("Tebex delivery requires review; no unverified items granted."));throw e;}
        }
    }
}
