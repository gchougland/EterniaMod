package com.hexvane.eterniamod.housing.relocation;

import java.util.*;
import org.bson.*;

/** Narrow migration of the original portal plinth after it has safely been packed. */
final class GuildWaygateSnapshot {
    private GuildWaygateSnapshot(){}
    static Optional<BsonDocument> withoutPlinth(BsonDocument saved){
        if(!saved.getArray("entities",new BsonArray()).isEmpty()||!saved.getArray("fluids",new BsonArray()).isEmpty())return Optional.empty();
        var blocks=saved.getArray("blocks",new BsonArray());if(blocks.size()!=10)return Optional.empty();
        var seen=new HashSet<String>();BsonDocument portal=null;
        for(var value:blocks){
            var block=value.asDocument();
            if(!Set.of("x","y","z","name","rotation","support").containsAll(block.keySet()))return Optional.empty();
            int x=block.getInt32("x").getValue(),y=block.getInt32("y").getValue(),z=block.getInt32("z").getValue();
            if(!seen.add(x+":"+y+":"+z))return Optional.empty();
            String name=block.getString("name").getValue();
            if(x==1&&y==1&&z==1&&name.equals("Eternia_Guild_Waygate"))portal=block.clone();
            else if(x<0||x>2||z<0||z>2||y!=0||!name.equals("Rock_Stone_Brick"))return Optional.empty();
        }
        if(portal==null)return Optional.empty();
        for(String axis:List.of("x","y","z"))portal.put(axis,new BsonInt32(0));
        var result=saved.clone();result.put("blocks",new BsonArray(List.of(portal)));
        return Optional.of(result);
    }
}
