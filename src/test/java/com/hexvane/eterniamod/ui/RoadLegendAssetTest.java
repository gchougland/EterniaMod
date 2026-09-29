package com.hexvane.eterniamod.ui;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
class RoadLegendAssetTest {
    @Test void roadUsesCustomHudWithoutLegendOrNativeHints() throws Exception {
        var loader=getClass().getClassLoader();
        try(var input=loader.getResourceAsStream("Server/Item/Items/Eternia_Spline_Road_Tool.json")) {
            var item=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(item.has("HudUI"));
            item.getAsJsonObject("Interactions").entrySet().forEach(e->assertFalse(e.getValue().getAsJsonObject().has("HudInputBindingEntry")));
        }
        try(var input=loader.getResourceAsStream("Common/UI/Custom/EterniaMod/ToolHudHotkeyRow.ui")) {
            String ui=new String(input.readAllBytes(),StandardCharsets.UTF_8);assertTrue(ui.contains("Label #KeyLabel"));assertFalse(ui.contains("HotkeyLabel"));
        }
    }
    @Test void aetherhavenResolverReadsKeyboardMouseAndModifiers() {
        var settings=ToolKeybindDisplay.parseSettingsOverrides("""
            {"InputActions":{"PrimaryItemAction":{"Bindings":[{"SourceType":1,"MouseButton":1}]},
            "Ability1ItemAction":{"Bindings":[{"SourceType":0,"Scancode":23,"RequiredModifiers":2}]}}}
            """);
        assertEquals("RMB",settings.get("PrimaryItemAction"));assertEquals("Ctrl+T",settings.get("Ability1ItemAction"));
        assertEquals("Q",ToolKeybindSlot.ABILITY1.defaultLabel());
    }
}
