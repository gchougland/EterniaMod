package com.hexvane.eterniamod.runtime;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CrownCatalogTest {
    @Test void releasedCatalogLoadsAndDeliversEveryExample(@TempDir Path data){var s=new EterniaServices(new InMemoryStore());GameplayContent.load(s,data);assertTrue(s.premium().items().size()>=10);UUID player=UUID.randomUUID();s.accounts().recordAuthenticatedLogin(player,"CatalogTest");s.premium().creditLocalExample(player,50000,"local-playground:catalog");for(var i:s.premium().items())s.premium().purchase(player,i.id(),i.revision(),i.price(),i.id());assertTrue(s.seasons().progress(player).stream().anyMatch(p->p.id().equals("eternia:foundations")&&p.paid()));}
}
