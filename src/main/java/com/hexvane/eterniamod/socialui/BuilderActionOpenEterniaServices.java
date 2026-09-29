package com.hexvane.eterniamod.socialui;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;
import java.util.EnumSet;
import javax.annotation.Nonnull;

public final class BuilderActionOpenEterniaServices extends BuilderActionBase {
    String section = "GREETER";
    @Nonnull @Override public String getShortDescription() { return "Open an Eternia service menu for the interacting player"; }
    @Nonnull @Override public String getLongDescription() { return getShortDescription(); }
    @Nonnull @Override public Action build(@Nonnull BuilderSupport support) { return new ActionOpenEterniaServices(this); }
    @Nonnull @Override public BuilderDescriptorState getBuilderDescriptorState() { return BuilderDescriptorState.Stable; }
    @Nonnull @Override public BuilderActionOpenEterniaServices readConfig(@Nonnull JsonElement data) {
        getString(data, "Section", value -> section = value, "GREETER", null, BuilderDescriptorState.Stable,
            "GREETER, HOUSING, GUILD, MAIL, SHOP, SEASON, COLLECTION, STORE, WORLDS or COMING_SOON", null);
        EterniaServicesPage.Section.valueOf(section);
        requireInstructionType(EnumSet.of(InstructionType.Interaction));
        return this;
    }
}
