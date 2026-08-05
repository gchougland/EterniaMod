package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.placement.BuildingPlacementClientPrefabPreview;
import com.hexvane.eterniamod.placement.BuildingPlacementNudgeUtil;
import com.hexvane.eterniamod.placement.BuildingPlacementSnapUtil;
import com.hexvane.eterniamod.placement.PlotFootprintUtil;
import com.hexvane.eterniamod.placement.PropPlacementCommit;
import com.hexvane.eterniamod.placement.PropPlacementNudgeUtil;
import com.hexvane.eterniamod.placement.PropPlacementOpenHelper;
import com.hexvane.eterniamod.placement.PropPlacementRotationUtil;
import com.hexvane.eterniamod.placement.PropPlacementSession;
import com.hexvane.eterniamod.placement.PropPlacementValidator;
import com.hexvane.eterniamod.placement.PropPlacementWireframeOverlay;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public final class PropPlacementPage extends EterniaInteractiveCustomUIPage<PropPlacementPage.PageData> {
    static final String MSG_UI = "eterniamod_ui.eterniamod.ui.propplacement";

    @Nonnull
    private final PropPlacementSession session;

    private int wireframeRefreshSerial;
    private boolean clientPrefabPreviewActive;

    @Nullable
    private String lastPreviewPropId;
    private int lastPreviewRotationSteps = -1;

    @Nullable
    private Vector3i lastPreviewOriginFloored;

    @Nullable
    private String lastValidationError;

    public PropPlacementPage(@Nonnull PlayerRef playerRef, @Nonnull PropPlacementSession session) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.session = session;
    }

    public static void applyLocalization(@Nonnull UICommandBuilder commandBuilder) {
        String p = MSG_UI;
        commandBuilder.set("#PlotPlacementTitle.TextSpans", Message.translation(p + ".title"));
        commandBuilder.set("#HeightLabel.TextSpans", Message.translation(p + ".heightSection"));
        commandBuilder.set("#CancelButton.TextSpans", Message.translation(p + ".cancel"));
        commandBuilder.set("#BtnZoomIn.TooltipTextSpans", Message.translation(p + ".zoomInTooltip"));
        commandBuilder.set("#BtnZoomOut.TooltipTextSpans", Message.translation(p + ".zoomOutTooltip"));
        commandBuilder.set("#BtnPanZm.TooltipTextSpans", Message.translation(p + ".panZmTooltip"));
        commandBuilder.set("#BtnPanXm.TooltipTextSpans", Message.translation(p + ".panXmTooltip"));
        commandBuilder.set("#BtnPanXp.TooltipTextSpans", Message.translation(p + ".panXpTooltip"));
        commandBuilder.set("#BtnPanZp.TooltipTextSpans", Message.translation(p + ".panZpTooltip"));
        commandBuilder.set("#BtnZm.TooltipTextSpans", Message.translation(p + ".moveZmTooltip"));
        commandBuilder.set("#BtnXm.TooltipTextSpans", Message.translation(p + ".moveXmTooltip"));
        commandBuilder.set("#BtnXp.TooltipTextSpans", Message.translation(p + ".moveXpTooltip"));
        commandBuilder.set("#BtnZp.TooltipTextSpans", Message.translation(p + ".moveZpTooltip"));
        commandBuilder.set("#BtnYm.TooltipTextSpans", Message.translation(p + ".moveYmTooltip"));
        commandBuilder.set("#BtnYp.TooltipTextSpans", Message.translation(p + ".moveYpTooltip"));
        commandBuilder.set("#BtnRotate.TooltipTextSpans", Message.translation(p + ".rotateTooltip"));
        commandBuilder.set("#SnapToLocationButton.TextSpans", Message.translation(p + ".snapToLocation"));
        commandBuilder.set("#SnapToLocationButton.TooltipTextSpans", Message.translation(p + ".snapToLocationTooltip"));
        commandBuilder.set("#PlaceButton.TextSpans", Message.translation(p + ".place"));
    }

    @Override
    public void build(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull UICommandBuilder commandBuilder,
        @Nonnull UIEventBuilder eventBuilder,
        @Nonnull Store<EntityStore> store
    ) {
        commandBuilder.append("EterniaMod/PropPlacementPage.ui");
        applyLocalization(commandBuilder);
        EterniaModPlugin plugin = EterniaModPlugin.get();
        PropDefinition def = plugin != null ? plugin.getPropCatalog().get(session.getPropId()) : null;
        String name = def != null && def.getDisplayName() != null ? def.getDisplayName() : session.getPropId();
        Vector3i sign = session.getAnchor();
        Vector3i prefabO =
            def != null
                ? def.resolvePrefabAnchorWorld(sign, session.getPrefabYaw())
                : new Vector3i(sign.x, sign.y, sign.z);
        commandBuilder.set("#Summary.TextSpans", Message.translation(MSG_UI + ".summary").param("prop", name));
        commandBuilder.set(
            "#Details.TextSpans",
            Message.translation(MSG_UI + ".detailsBlock")
                .param("sx", sign.x)
                .param("sy", sign.y)
                .param("sz", sign.z)
                .param("ox", prefabO.x)
                .param("oy", prefabO.y)
                .param("oz", prefabO.z)
                .param("step", session.getRotationSteps())
        );
        commandBuilder.set("#PlotTypeDropdown.Visible", false);
        commandBuilder.set("#PlotTypeLabel.Visible", false);
        commandBuilder.set("#MoveConfirmGroup.Visible", false);
        commandBuilder.set("#PlaceButton.Visible", true);
        commandBuilder.set("#BirdsEyeToggle.Visible", false);
        commandBuilder.set("#BirdsEyeZoomRow.Visible", false);
        commandBuilder.set("#BirdsEyePanColumn.Visible", false);
        lastValidationError = resolveValidationError(ref, store);
        boolean canPlace = lastValidationError == null;
        commandBuilder.set("#PlaceButton.Disabled", !canPlace);
        if (lastValidationError != null) {
            commandBuilder.set("#MoveConfirmGroup.Visible", true);
            commandBuilder.set("#MoveConfirmButton.Visible", false);
            commandBuilder.set("#MoveConfirmBackButton.Visible", false);
            commandBuilder.set(
                "#MoveConfirmText.TextSpans",
                Message.translation("eterniamod_common.eterniamod.common." + lastValidationError)
            );
        } else {
            commandBuilder.set("#MoveConfirmGroup.Visible", false);
        }
        bind(eventBuilder, "#SnapToLocationButton", "SnapToLocation");
        bind(eventBuilder, "#BtnXm", "MoveXm");
        bind(eventBuilder, "#BtnXp", "MoveXp");
        bind(eventBuilder, "#BtnZm", "MoveZm");
        bind(eventBuilder, "#BtnZp", "MoveZp");
        bind(eventBuilder, "#BtnYm", "MoveYm");
        bind(eventBuilder, "#BtnYp", "MoveYp");
        bind(eventBuilder, "#BtnRotate", "Rotate");
        bind(eventBuilder, "#PlaceButton", "Place");
        bind(eventBuilder, "#CancelButton", "Cancel");
        if (wireframeRefreshSerial == 0) {
            scheduleInitialPreview(ref, store);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        if (data.action == null) {
            return;
        }
        float yawRad = BuildingPlacementNudgeUtil.getPlayerYawRadians(ref, store);
        switch (data.action) {
            case "MoveXm" ->
                PropPlacementNudgeUtil.nudgeHorizontal(
                    session, yawRad, BuildingPlacementNudgeUtil.Horizontal.NEG_X
                );
            case "MoveXp" ->
                PropPlacementNudgeUtil.nudgeHorizontal(
                    session, yawRad, BuildingPlacementNudgeUtil.Horizontal.POS_X
                );
            case "MoveZm" ->
                PropPlacementNudgeUtil.nudgeHorizontal(
                    session, yawRad, BuildingPlacementNudgeUtil.Horizontal.NEG_Z
                );
            case "MoveZp" ->
                PropPlacementNudgeUtil.nudgeHorizontal(
                    session, yawRad, BuildingPlacementNudgeUtil.Horizontal.POS_Z
                );
            case "MoveYm" -> session.nudge(0, -1, 0);
            case "MoveYp" -> session.nudge(0, 1, 0);
            case "Rotate" -> applyRotate();
            case "Cancel" -> {
                scheduleCancel(ref, store);
                return;
            }
            case "Place" -> {
                schedulePlace(ref, store);
                return;
            }
            case "SnapToLocation" -> {
                BuildingPlacementSnapUtil.snapPropSessionToPlayer(session, ref, store);
                schedulePreviewAndRebuild(ref, store);
                return;
            }
            default -> {
                return;
            }
        }
        schedulePreviewAndRebuild(ref, store);
    }

    private void applyRotate() {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        PropDefinition def = plugin != null ? plugin.getPropCatalog().get(session.getPropId()) : null;
        if (def == null) {
            session.rotateClockwise90();
            return;
        }
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            session.rotateClockwise90();
            return;
        }
        IPrefabBuffer buf = PrefabBufferUtil.getCached(prefabPath);
        PropPlacementRotationUtil.rotateClockwise90PreservingFootprintCenter(session, def, buf);
    }

    private void schedulePreviewAndRebuild(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        final int serial = ++wireframeRefreshSerial;
        world.execute(
            () -> {
                if (!ref.isValid() || serial != wireframeRefreshSerial) {
                    return;
                }
                refreshPreview(ref, store);
                if (!ref.isValid() || serial != wireframeRefreshSerial) {
                    return;
                }
                rebuild();
            }
        );
    }

    private void scheduleInitialPreview(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        World world = store.getExternalData().getWorld();
        final int serial = ++wireframeRefreshSerial;
        world.execute(
            () -> {
                if (!ref.isValid() || serial != wireframeRefreshSerial) {
                    return;
                }
                refreshPreview(ref, store);
            }
        );
    }

    private void scheduleCancel(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        store.getExternalData().getWorld().execute(
            () -> {
                if (!ref.isValid()) {
                    return;
                }
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
                if (pr != null && uc != null) {
                    PropPlacementOpenHelper.cancelActive(pr, uc.getUuid());
                } else if (pr != null) {
                    clearPreview(pr);
                }
                close();
            }
        );
    }

    private void schedulePlace(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        store.getExternalData().getWorld().execute(
            () -> {
                if (!ref.isValid()) {
                    return;
                }
                if (tryPlace(ref, store)) {
                    PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                    UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
                    if (pr != null && uc != null) {
                        PropPlacementOpenHelper.cancelActive(pr, uc.getUuid());
                    } else if (pr != null) {
                        clearPreview(pr);
                    }
                    close();
                } else {
                    refreshPreview(ref, store);
                    rebuild();
                }
            }
        );
    }

    @Nullable
    private String resolveValidationError(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
        if (plugin == null || uc == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = plotManager.getPlot(session.getPlotId());
        if (plot == null) {
            return "notInPlot";
        }
        PropDefinition def = plugin.getPropCatalog().get(session.getPropId());
        if (def == null) {
            return "unknownProp";
        }
        return PropPlacementValidator.validate(
            world, plot, uc.getUuid(), session.getAnchor(), session.getPrefabYaw(), def
        );
    }

    private boolean tryPlace(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
        if (plugin == null || uc == null) {
            return false;
        }
        World world = store.getExternalData().getWorld();
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = plotManager.getPlot(session.getPlotId());
        if (plot == null) {
            sendError(store, ref, Message.translation("eterniamod_common.eterniamod.common.notInPlot"));
            return false;
        }
        PropDefinition def = plugin.getPropCatalog().get(session.getPropId());
        if (def == null) {
            sendError(store, ref, Message.translation("eterniamod_common.eterniamod.common.unknownProp"));
            return false;
        }
        String errKey =
            PropPlacementValidator.validate(
                world, plot, uc.getUuid(), session.getAnchor(), session.getPrefabYaw(), def
            );
        if (errKey != null) {
            sendError(store, ref, Message.translation("eterniamod_common.eterniamod.common." + errKey));
            return false;
        }
        if (!PropPlacementCommit.place(ref, store, session, plugin)) {
            sendError(store, ref, Message.translation("eterniamod_common.eterniamod.common.placeFailed"));
            return false;
        }
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (pr != null) {
            pr.sendMessage(Message.translation("eterniamod_common.eterniamod.common.propPlaced"));
        }
        return true;
    }

    private void refreshPreview(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (!ref.isValid()) {
            return;
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        PlayerRef pr = playerRef;
        if (plugin == null) {
            return;
        }
        PropDefinition def = plugin.getPropCatalog().get(session.getPropId());
        if (def == null) {
            clearClientPrefabPreview(pr);
            PropPlacementWireframeOverlay.clearFor(pr);
            return;
        }
        IPrefabBuffer buf = PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath());
        if (buf == null) {
            clearClientPrefabPreview(pr);
            PropPlacementWireframeOverlay.clearFor(pr);
            return;
        }
        UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uc == null) {
            clearClientPrefabPreview(pr);
            PropPlacementWireframeOverlay.clearFor(pr);
            return;
        }
        World world = store.getExternalData().getWorld();
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = plotManager.getPlot(session.getPlotId());
        if (plot == null) {
            clearClientPrefabPreview(pr);
            PropPlacementWireframeOverlay.clearFor(pr);
            return;
        }
        lastValidationError = resolveValidationError(ref, store);
        Vector3i prefabOrigin = def.resolvePrefabAnchorWorld(session.getAnchor(), session.getPrefabYaw());
        HubPlotFootprint propFootprint = PlotFootprintUtil.computeFootprint(prefabOrigin, session.getPrefabYaw(), buf);
        syncClientPrefabPreview(pr, def, prefabOrigin);
        PropPlacementWireframeOverlay.send(pr, plot.getFootprint(), propFootprint, lastValidationError == null);
    }

    private void syncClientPrefabPreview(
        @Nonnull PlayerRef pr,
        @Nonnull PropDefinition def,
        @Nonnull Vector3i prefabOriginWorld
    ) {
        Vector3i floored =
            BuildingPlacementClientPrefabPreview.flooredClientPreviewOrigin(
                prefabOriginWorld,
                session,
                session.getPrefabYaw()
            );
        boolean rotationOrPropChanged =
            !clientPrefabPreviewActive
                || !session.getPropId().equals(lastPreviewPropId)
                || session.getRotationSteps() != lastPreviewRotationSteps;
        boolean positionChanged = lastPreviewOriginFloored == null || !lastPreviewOriginFloored.equals(floored);
        if (!rotationOrPropChanged && !positionChanged) {
            return;
        }
        if (clientPrefabPreviewActive && positionChanged) {
            BuildingPlacementClientPrefabPreview.hide(pr);
        }
        boolean ok =
            BuildingPlacementClientPrefabPreview.sendFull(
                pr,
                def.getPrefabPath(),
                session.getRotationSteps(),
                prefabOriginWorld,
                session.getPrefabYaw(),
                session
            );
        if (!ok) {
            clearClientPrefabPreview(pr);
            return;
        }
        clientPrefabPreviewActive = true;
        lastPreviewPropId = session.getPropId();
        lastPreviewRotationSteps = session.getRotationSteps();
        lastPreviewOriginFloored = floored;
    }

    private void clearClientPrefabPreview(@Nullable PlayerRef pr) {
        if (pr == null) {
            return;
        }
        if (clientPrefabPreviewActive) {
            BuildingPlacementClientPrefabPreview.hide(pr);
        }
        clientPrefabPreviewActive = false;
        lastPreviewPropId = null;
        lastPreviewRotationSteps = -1;
        lastPreviewOriginFloored = null;
    }

    private void clearPreview(@Nullable PlayerRef pr) {
        clearClientPrefabPreview(pr);
        PropPlacementWireframeOverlay.clearFor(pr);
        BuildingPlacementClientPrefabPreview.clearSessionCache(session);
    }

    private void sendError(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Message message) {
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (pr != null) {
            pr.sendMessage(message);
        }
    }

    private static void bind(@Nonnull UIEventBuilder eventBuilder, @Nonnull String selector, @Nonnull String action) {
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            selector,
            new EventData().append("Action", action),
            false
        );
    }

    public static final class PageData {
        public static final BuilderCodec<PageData> CODEC =
            BuilderCodec.builder(PageData.class, PageData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, a) -> d.action = a, d -> d.action)
                .add()
                .build();

        @Nullable
        public String action;
    }
}
