package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class MailService extends DomainSupport {
    public record Message(String id,UUID sender,UUID recipient,String subject,String body,List<String> attachments,boolean claimed,boolean read,Instant sentAt) {public Message{attachments=List.copyOf(attachments);}}
    private final EscrowService escrow;
    MailService(TransactionalStore s,Clock c,Supplier<UUID> i,EscrowService escrow){super(s,c,i);this.escrow=escrow;}
    public Message send(UUID sender,UUID recipient,String subject,String body,List<String> attachments,String receiptId) {
        text(subject,"mail subject",100);text(body,"mail body",2000);attachments=List.copyOf(attachments);
        require(attachments.size()<=5&&new HashSet<>(attachments).size()==attachments.size(),INVALID_INPUT,"At most five distinct attachment stacks");
        List<String> items=attachments;
        return store.transaction(tx->{var input=fields("sender",sender,"recipient",recipient,"subject",subject,"body",body,"attachments",String.join("\n",items));
            String id=key(receiptId);if(!receipt(tx,"mail_receipt",receiptId,input))return message(row(tx,"mail",id));
            row(tx,"account",sender.toString());row(tx,"account",recipient.toString());
            require(tx.find("mail_block",recipient+":"+sender).filter(r->r.value("blocked").equals("true")).isEmpty(),FORBIDDEN,"Recipient does not accept this sender");
            long active=tx.scan("mail").stream().filter(r->r.value("recipient").equals(recipient.toString())&&!r.value("archived").equals("true")).count();require(active<20,CAPACITY,"Recipient mailbox is full");
            for(String item:items){var r=escrow.holdIn(tx,item,Owner.player(sender),"mail:"+id);require(Boolean.parseBoolean(r.value("transferable")),FORBIDDEN,"Bound attachment cannot be mailed");}
            input.putAll(fields("claimed",items.isEmpty(),"read",false,"archived",false,"at",clock.instant()));return message(tx.save("mail",id,0,input));});
    }
    public List<Message> inbox(UUID recipient){return store.transaction(tx->tx.scan("mail").stream().filter(r->r.value("recipient").equals(recipient.toString())&&!r.value("archived").equals("true")).map(MailService::message).toList());}
    public List<Message> sent(UUID sender){return store.transaction(tx->tx.scan("mail").stream().filter(r->r.value("sender").equals(sender.toString())).map(MailService::message).toList());}
    public void markRead(UUID recipient,String id){store.transaction(tx->{var r=recipient(tx,recipient,id);save(tx,r,"read",true);return null;});}
    public void claim(UUID recipient,String id){store.transaction(tx->{claimIn(tx,recipient,id);return null;});}
    public void claimAll(UUID recipient){store.transaction(tx->{for(var r:tx.scan("mail"))if(r.value("recipient").equals(recipient.toString())&&!r.value("claimed").equals("true"))claimIn(tx,recipient,r.key());return null;});}
    private void claimIn(TransactionalStore.Transaction tx,UUID recipient,String id){
        var r=recipient(tx,recipient,id);if(r.value("claimed").equals("true"))return;
        for(String item:items(r)){var stock=row(tx,"escrow",item);escrow.deliverHeldIn(tx,item,"mail:"+id,Owner.player(recipient),stock.number("remaining"),"mail:"+id+":"+item);}
        save(tx,r,"claimed",true,"read",true);
    }
    public void archive(UUID recipient,String id){store.transaction(tx->{var r=recipient(tx,recipient,id);require(r.value("claimed").equals("true"),INVALID_STATE,"Claim attachments before archiving");save(tx,r,"archived",true);return null;});}
    public void returnAttachments(UUID recipient,String id){
        store.transaction(tx->{var r=recipient(tx,recipient,id);require(!r.value("claimed").equals("true"),INVALID_STATE,"Attachments already claimed");Owner sender=Owner.player(UUID.fromString(r.value("sender")));
            for(String item:items(r)){var stock=row(tx,"escrow",item);escrow.deliverHeldIn(tx,item,"mail:"+id,sender,stock.number("remaining"),"mail-return:"+id+":"+item);}save(tx,r,"claimed",true,"read",true,"archived",true);return null;});
    }
    public void block(UUID recipient,UUID sender,boolean blocked){store.transaction(tx->{String id=recipient+":"+sender;var old=tx.find("mail_block",id);tx.save("mail_block",id,old.map(TransactionalStore.Row::revision).orElse(0L),fields("blocked",blocked));return null;});}
    private static TransactionalStore.Row recipient(TransactionalStore.Transaction tx,UUID recipient,String id){var r=row(tx,"mail",id);require(r.value("recipient").equals(recipient.toString()),FORBIDDEN,"Message belongs to another recipient");return r;}
    private static List<String> items(TransactionalStore.Row r){return r.value("attachments").isEmpty()?List.of():List.of(r.value("attachments").split("\n"));}
    private static Message message(TransactionalStore.Row r){return new Message(r.key(),UUID.fromString(r.value("sender")),UUID.fromString(r.value("recipient")),r.value("subject"),r.value("body"),items(r),Boolean.parseBoolean(r.value("claimed")),Boolean.parseBoolean(r.value("read")),Instant.parse(r.value("at")));}
}
