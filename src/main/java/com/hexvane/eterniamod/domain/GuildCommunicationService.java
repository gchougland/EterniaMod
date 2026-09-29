package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Small persistent guild notice board; live chat transport stays in the native adapter. */
public final class GuildCommunicationService extends DomainSupport {
    public record Post(UUID id,UUID guild,UUID author,String title,String body,boolean pinned,boolean removed,long revision){}
    GuildCommunicationService(TransactionalStore store,Clock clock,Supplier<UUID> ids){super(store,clock,ids);}
    public List<Post> board(UUID actor){return store.transaction(tx->{
        UUID guild=membership(tx,actor,"board.read");
        return tx.scan("guild_post").stream().filter(r->r.value("guild").equals(guild.toString())&&!r.value("removed").equals("true"))
            .sorted(Comparator.<TransactionalStore.Row,Boolean>comparing(r->r.value("pinned").equals("true")).reversed().thenComparing(r->r.value("created"),Comparator.reverseOrder()).thenComparing(TransactionalStore.Row::key))
            .map(GuildCommunicationService::post).toList();
    });}
    public Post post(UUID actor,String title,String body,String receiptId){
        return post(actor,null,title,body,receiptId);
    }
    public Post post(UUID actor,UUID expectedGuild,String title,String body,String receiptId){
        validateText(title,body);
        return store.transaction(tx->{
            UUID guild=membership(tx,actor,"board.post");var input=fields("actor",actor,"guild",guild,"title",title,"body",body);
            require(expectedGuild==null||expectedGuild.equals(guild),CONFLICT,"Your guild changed. Reopen the board before posting.");
            String receiptKey=key(text(receiptId,"post receipt",240));var prior=tx.find("guild_post_receipt",receiptKey);
            if(prior.isPresent()){
                for(var entry:input.entrySet())require(prior.get().value(entry.getKey()).equals(entry.getValue()),CONFLICT,"Post receipt was reused with different input");
                return post(row(tx,"guild_post",prior.get().value("post")));
            }
            require(tx.scan("guild_post").stream().filter(r->r.value("guild").equals(guild.toString())&&!r.value("removed").equals("true")).count()<100,CAPACITY,"The board is full. Ask an officer to remove old notices.");
            UUID id=ids.get();input.put("post",id.toString());tx.save("guild_post_receipt",receiptKey,0,input);
            return post(tx.save("guild_post",id.toString(),0,fields("guild",guild,"author",actor,"title",title,"body",body,"pinned",false,"removed",false,"created",clock.instant())));
        });
    }
    public Post edit(UUID actor,UUID postId,String title,String body){
        return edit(actor,postId,-1,title,body);
    }
    public Post edit(UUID actor,UUID postId,long expectedRevision,String title,String body){
        validateText(title,body);
        return store.transaction(tx->{
            UUID guild=membership(tx,actor,"board.post");var post=ownedGuildPost(tx,guild,postId);
            require(expectedRevision<0||post.revision()==expectedRevision,CONFLICT,"The notice changed. Reopen it before editing.");
            require(post.value("author").equals(actor.toString()),FORBIDDEN,"You may edit only your own notice");
            return post(save(tx,post,"title",title,"body",body));
        });
    }
    public Post moderate(UUID actor,UUID postId,boolean pinned,boolean removed){return moderate(actor,postId,-1,pinned,removed);}
    public Post moderate(UUID actor,UUID postId,long expectedRevision,boolean pinned,boolean removed){return store.transaction(tx->{
        UUID guild=membership(tx,actor,"board.moderate");var post=ownedGuildPost(tx,guild,postId);
        require(expectedRevision<0||post.revision()==expectedRevision,CONFLICT,"The notice changed. Reopen it before moderating.");
        if(pinned&&!removed&&!post.value("pinned").equals("true"))require(tx.scan("guild_post").stream().filter(r->r.value("guild").equals(guild.toString())&&r.value("pinned").equals("true")&&!r.value("removed").equals("true")).count()<5,CAPACITY,"Unpin another notice first; the board has five pinned notices");
        return post(save(tx,post,"pinned",pinned&&!removed,"removed",removed));
    });}
    private static UUID membership(TransactionalStore.Transaction tx,UUID actor,String capability){
        UUID guild=UUID.fromString(GuildService.currentMember(tx,actor).value("guild"));GuildService.requireCapability(tx,actor,guild,capability);return guild;
    }
    private static TransactionalStore.Row ownedGuildPost(TransactionalStore.Transaction tx,UUID guild,UUID id){
        var post=row(tx,"guild_post",id.toString());require(post.value("guild").equals(guild.toString()),FORBIDDEN,"This notice belongs to another guild");
        require(!post.value("removed").equals("true"),INVALID_STATE,"This notice was removed");return post;
    }
    private static void validateText(String title,String body){text(title,"notice title",80);text(body,"notice body",2000);require(title.chars().noneMatch(Character::isISOControl)&&body.chars().noneMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'),INVALID_INPUT,"Notice contains unsupported control characters");}
    private static Post post(TransactionalStore.Row row){return new Post(UUID.fromString(row.key()),UUID.fromString(row.value("guild")),UUID.fromString(row.value("author")),row.value("title"),row.value("body"),Boolean.parseBoolean(row.value("pinned")),Boolean.parseBoolean(row.value("removed")),row.revision());}
}
