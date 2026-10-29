package com.hexvane.eterniamod.authoring;

import com.google.gson.*;
import com.hexvane.eterniamod.runtime.RuntimeConfig;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Sends admin content to the website with the shared bridge token. The website never sees a player credential. */
public final class WebsiteSync {
    public record Result(int revision,boolean unchanged){}
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base,token;
    private WebsiteSync(String base,String token){this.base=base;this.token=token;}
    public static WebsiteSync from(RuntimeConfig config){
        String url=config==null?"":config.websiteUrl().replaceAll("/+$","");
        return new WebsiteSync(url,config==null?"":config.bridgeToken());
    }
    public boolean configured(){return !base.isBlank()&&token.length()>=32;}
    public CompletableFuture<Result> content(JsonObject body){return post("/api/server/content",body);}
    public CompletableFuture<Result> season(JsonObject body){return post("/api/server/seasons",body);}
    private CompletableFuture<Result> post(String path,JsonObject body){
        if(!configured())return CompletableFuture.failedFuture(new IllegalStateException("The website is not connected on this server."));
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8)).build();
        return HTTP.sendAsync(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response->{
            JsonObject reply;try{reply=JsonParser.parseString(response.body()).getAsJsonObject();}catch(RuntimeException e){reply=new JsonObject();}
            if(response.statusCode()==401)throw new IllegalStateException("The website did not accept this server's key. Check that both use the same bridge token.");
            if(response.statusCode()/100!=2){
                String error=reply.has("error")&&reply.get("error").isJsonPrimitive()?reply.get("error").getAsString():"The website could not save it right now.";
                throw new IllegalStateException(error.length()>200?error.substring(0,200):error);
            }
            return new Result(reply.has("revision")?reply.get("revision").getAsInt():0,reply.has("unchanged")&&reply.get("unchanged").getAsBoolean());
        });
    }
    /** The message to show for a failed upload, without Java exception wrappers. */
    public static String reason(Throwable error){
        Throwable cause=error;while(cause.getCause()!=null&&(cause instanceof java.util.concurrent.CompletionException||cause instanceof java.util.concurrent.ExecutionException))cause=cause.getCause();
        if(cause instanceof java.net.http.HttpTimeoutException)return "The website did not answer in time.";
        if(cause instanceof java.io.IOException)return "The website could not be reached.";
        return cause.getMessage()==null?"The website could not save it right now.":cause.getMessage();
    }
}
