package com.hexvane.eterniamod.guildroads;

import com.hexvane.eterniamod.domain.JournalService;
import java.util.*;
import static com.hexvane.eterniamod.guildroads.GuildRoadRegistry.*;

/** Decisions at the registry/domain-journal boundary, independent of native world state. */
final class RoadRecovery {
    static boolean removalPredecessor(Road current,Road next,JournalService.Operation operation,JournalService.Operation previous){
        return current.state()==State.ACTIVE&&operation.kind().equals("GUILD_ROAD_REMOVE")&&previous!=null&&previous.id().equals(current.operation())&&previous.kind().equals("GUILD_ROAD_ADD")&&previous.state()==JournalService.State.COMPLETED&&previous.owner().equals(operation.owner())
            &&current.state(next.state(),next.operation()).equals(next);
    }
    static List<Road> protectedRoads(List<Road> registry,Map<UUID,Road> emergency){
        var result=new LinkedHashMap<UUID,Road>();for(var road:registry)if(road.state()!=State.REMOVED)result.put(road.id(),road);
        result.putAll(emergency);return List.copyOf(result.values());
    }
    private RoadRecovery(){}
}
