package com.hexvane.eterniamod.housing;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class HousingUseCapabilitiesTest {
    @Test void normalMemberServicesDoNotRequireUnrelatedStoragePermission() {
        assertEquals(Set.of("housing.door.use"),HousingUseCapabilities.required(false,false,false,true,false,false,Set.of("DoorInteraction")));
        assertEquals(Set.of("housing.bed.use"),HousingUseCapabilities.required(false,false,false,false,true,false,Set.of("BedInteraction")));
        assertEquals(Set.of("housing.bench.use"),HousingUseCapabilities.required(false,false,false,false,false,true,Set.of("OpenBenchPageInteraction")));
        assertEquals(Set.of("housing.visit"),HousingUseCapabilities.required(false,false,true,false,false,false,Set.of("EterniaHousingTool")));
    }
    @Test void ContainersAndUnknownOrMixedLedgerBehaviorRemainProtected() {
        assertEquals(Set.of("housing.container.open"),HousingUseCapabilities.required(true,false,true,true,true,true,Set.of("EterniaHousingTool","DoorInteraction")));
        assertEquals(Set.of("housing.container.open"),HousingUseCapabilities.required(false,false,true,false,false,false,Set.of("EterniaHousingTool","UnknownAction")));
        assertEquals(Set.of("housing.container.open"),HousingUseCapabilities.required(false,false,false,false,false,false,Set.of("UnknownAction")));
        assertEquals(Set.of("housing.bench.use","housing.container.open"),HousingUseCapabilities.required(false,true,false,false,false,true,Set.of("OpenProcessingBenchInteraction")));
    }
    @Test void onlyInventoryFreeKnownPortalReceivesThePublicServiceClassification() {
        assertEquals(Set.of(HousingUseCapabilities.PORTAL_SERVICE),HousingUseCapabilities.required(false,false,false,true,false,false,false,Set.of("EterniaHousingTool")));
        assertEquals(Set.of("housing.container.open"),HousingUseCapabilities.required(true,false,false,true,false,false,false,Set.of("EterniaHousingTool")));
        assertEquals(Set.of("housing.container.open"),HousingUseCapabilities.required(false,false,false,true,false,false,false,Set.of("EterniaHousingTool","UnknownAction")));
        assertEquals(Set.of("housing.container.open"),HousingUseCapabilities.required(false,false,false,false,false,false,false,Set.of("EterniaHousingTool")));
    }
}
