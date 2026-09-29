package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GuildCommunicationServiceTest {
    private record Fixture(EterniaServices services,UUID leader,UUID member,UUID outsider){}
    private Fixture fixture(TransactionalStore store){
        var services=new EterniaServices(store);UUID leader=UUID.randomUUID(),member=UUID.randomUUID(),other=UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(leader,"Leader");services.accounts().recordAuthenticatedLogin(member,"Member");services.accounts().recordAuthenticatedLogin(other,"Outsider");
        services.guilds().create(leader,"Board Guild","board-guild");services.guilds().acceptInvite(member,services.guilds().invite(leader,member));services.guilds().create(other,"Other Guild","other-guild");
        return new Fixture(services,leader,member,other);
    }
    @Test void retryPersistsOneNoticeAndMembershipBoundsReadEditAndModeration(){
        var f=fixture(new InMemoryStore());var board=f.services.guildCommunications();
        var post=board.post(f.member,"Guild run","Meet at the hall.","notice");assertEquals(post,board.post(f.member,"Guild run","Meet at the hall.","notice"));
        assertThrows(DomainException.class,()->board.post(f.member,"Changed","Meet at the hall.","notice"));
        assertEquals(1,board.board(f.leader).size());assertTrue(board.board(f.outsider).isEmpty());
        assertThrows(DomainException.class,()->board.edit(f.leader,post.id(),"Changed","Leader cannot rewrite authors."));
        assertThrows(DomainException.class,()->board.moderate(f.member,post.id(),true,false));
        assertThrows(DomainException.class,()->board.moderate(f.outsider,post.id(),false,true));
        assertEquals("New meeting time",board.edit(f.member,post.id(),"New meeting time","Meet tomorrow.").title());
        assertThrows(DomainException.class,()->board.edit(f.member,post.id(),post.revision(),"Old page","Must not overwrite newer text."));
        assertThrows(DomainException.class,()->board.moderate(f.leader,post.id(),post.revision(),false,true));
        assertTrue(board.moderate(f.leader,post.id(),true,false).pinned());
        f.services.guilds().leave(f.member);
        assertThrows(DomainException.class,()->board.board(f.member));assertThrows(DomainException.class,()->board.edit(f.member,post.id(),"Stale page","No longer a member."));
        f.services.guilds().acceptInvite(f.member,f.services.guilds().invite(f.outsider,f.member));
        assertThrows(DomainException.class,()->board.post(f.member,post.guild(),"Old guild draft","Must not reach another guild.","stale-draft"));
        board.moderate(f.leader,post.id(),false,true);assertTrue(board.board(f.leader).isEmpty());
        assertThrows(DomainException.class,()->board.edit(f.leader,post.id(),"Removed","Cannot restore by edit."));
    }
    @Test void duplicateConcurrentPostSurvivesRestart(@TempDir Path directory)throws Exception{
        UUID leader;GuildCommunicationService.Post post;
        Path data=directory.resolve("authority.bin");
        try(var store=new LocalFileStore(data);var executor=Executors.newFixedThreadPool(2)){
            var f=fixture(store);leader=f.leader;var a=executor.submit(()->f.services.guildCommunications().post(f.member,"Announcement","Bring supplies.","concurrent"));
            var b=executor.submit(()->f.services.guildCommunications().post(f.member,"Announcement","Bring supplies.","concurrent"));post=a.get();assertEquals(post,b.get());
        }
        try(var store=new LocalFileStore(data)){var board=new EterniaServices(store).guildCommunications();assertEquals(List.of(post),board.board(leader));}
    }
    @Test void pinAndBoardCapacityAreBoundedWithoutChangingExistingNotices(){
        var f=fixture(new InMemoryStore());var board=f.services.guildCommunications();var posts=new ArrayList<GuildCommunicationService.Post>();
        for(int i=0;i<100;i++)posts.add(board.post(f.member,"Notice "+i,"Text","post-"+i));
        for(int i=0;i<5;i++)board.moderate(f.leader,posts.get(i).id(),true,false);
        assertThrows(DomainException.class,()->board.moderate(f.leader,posts.get(5).id(),true,false));
        assertThrows(DomainException.class,()->board.post(f.member,"Too many","Text","extra"));
        board.moderate(f.leader,posts.get(0).id(),false,true);board.post(f.member,"Replacement","Text","replacement");assertEquals(100,board.board(f.leader).size());
    }
}
