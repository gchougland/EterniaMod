package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.placement.BuildingPlacementCameraUtil;
import com.hexvane.eterniamod.placement.BuildingPlacementDebugLog;
import com.hexvane.eterniamod.placement.BuildingPlacementClientPrefabPreview;
import com.hexvane.eterniamod.placement.BuildingPlacementCommit;
import com.hexvane.eterniamod.placement.BuildingPlacementOpenHelper;
import com.hexvane.eterniamod.placement.BuildingPlacementNudgeUtil;
import com.hexvane.eterniamod.placement.BuildingPlacementRotationUtil;
import com.hexvane.eterniamod.placement.BuildingPlacementSession;
import com.hexvane.eterniamod.placement.BuildingPlacementSessions;
import com.hexvane.eterniamod.placement.BuildingPlacementSnapUtil;
import com.hexvane.eterniamod.placement.BuildingPlacementValidator;
import com.hexvane.eterniamod.placement.BuildingPlacementWireframeOverlay;
import com.hexvane.eterniamod.placement.PlotFootprintUtil;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

public final class BuildingPlacementPage extends EterniaInteractiveCustomUIPage<BuildingPlacementPage.PageData> {
    static final String MSG_UI = "eterniamod_ui.eterniamod.ui.buildingplacement";

    @Nonnull
    private final BuildingPlacementSession session;

    private boolean birdsEyeEnabled;
    private float birdsEyeDistance = BuildingPlacementCameraUtil.DEFAULT_DISTANCE;
    private int smoothPanGeneration;
    private int wireframeRefreshSerial;
    private boolean clientPrefabPreviewActive;

    @Nullable
    private String lastPreviewBuildingId;
    private int lastPreviewRotationSteps = -1;

    @Nullable
    private Vector3i lastPreviewOriginFloored;

    public BuildingPlacementPage(@Nonnull PlayerRef playerRef, @Nonnull BuildingPlacementSession session) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.session = session;
    }

    public static void applyLocalization(@Nonnull UICommandBuilder commandBuilder) {
        String p = MSG_UI;
        commandBuilder.set("#PlotPlacementTitle.TextSpans", Message.translation(p + ".title"));
        commandBuilder.set("#HeightLabel.TextSpans", Message.translation(p + ".heightSection"));
        commandBuilder.set("#CancelButton.TextSpans", Message.translation(p + ".cancel"));
        commandBuilder.set("#BirdsEyeToggle #BirdsEyeLabel.TextSpans", Message.translation(p + ".birdsEye"));
        commandBuilder.set("#BirdsEyeToggle #BirdsEyeLabel.TooltipTextSpans", Message.translation(p + ".birdsEyeTooltip"));
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
        commandBuilder.append("EterniaMod/BuildingPlacementPage.ui");bindHome(eventBuilder);
        applyLocalization(commandBuilder);
        EterniaModPlugin plugin = EterniaModPlugin.get();
        BuildingDefinition def =
            plugin != null ? plugin.getBuildingCatalog().get(session.getBuildingId()) : null;
        String name = def != null && def.getDisplayName() != null ? def.getDisplayName() : session.getBuildingId();
        Vector3i sign = session.getAnchor();
        Vector3i prefabO =
            def != null
                ? def.resolvePrefabAnchorWorld(sign, session.getPrefabYaw())
                : new Vector3i(sign.x, sign.y, sign.z);
        commandBuilder.set("#Summary.TextSpans", Message.translation(MSG_UI + ".summary").param("building", name));
        var manager=plugin==null?null:EterniaWorldRegistries.getOrCreateHubPlotManager(store.getExternalData().getWorld(),plugin);
        var plot=manager==null?null:manager.getPlot(session.getPlotId());
        String problem=plot==null||def==null?"unavailable":BuildingPlacementValidator.validate(store.getExternalData().getWorld(),manager,plot,playerRef.getUuid(),sign,session.getPrefabYaw(),def,plugin);
        commandBuilder.set("#Details.TextSpans",problem==null?Message.raw("Ready to place. You can adjust the position or rotate the house before confirming."):Message.translation("eterniamod_common.eterniamod.common."+problem));
        bind(eventBuilder,"#FindValidPosition","FindValidPosition");
        commandBuilder.set("#PlotTypeDropdown.Visible", false);
        commandBuilder.set("#PlotTypeLabel.Visible", false);
        commandBuilder.set("#MoveConfirmGroup.Visible", false);
        commandBuilder.set("#PlaceButton.Visible", true);
        commandBuilder.set("#BirdsEyeToggle #CheckBox.Value", birdsEyeEnabled);
        commandBuilder.set("#BirdsEyeZoomRow.Visible", birdsEyeEnabled);
        commandBuilder.set("#BirdsEyePanColumn.Visible", birdsEyeEnabled);
        commandBuilder.set("#BirdsEyeDistanceSlider.Value", birdsEyeDistance);
        commandBuilder.set("#BirdsEyeDistanceValue.TextSpans", Message.raw(String.format("%.0f", birdsEyeDistance)));
        commandBuilder.set("#BtnZoomOut.Disabled", birdsEyeDistance >= BuildingPlacementCameraUtil.MAX_DISTANCE - 0.01f);
        commandBuilder.set("#BtnZoomIn.Disabled", birdsEyeDistance <= BuildingPlacementCameraUtil.MIN_DISTANCE + 0.01f);
        bind(eventBuilder, "#SnapToLocationButton", "SnapToLocation");
        bind(eventBuilder, "#BtnXm", "MoveXm");
        bind(eventBuilder, "#BtnXp", "MoveXp");
        bind(eventBuilder, "#BtnZm", "MoveZm");
        bind(eventBuilder, "#BtnZp", "MoveZp");
        bind(eventBuilder, "#BtnYm", "MoveYm");
        bind(eventBuilder, "#BtnYp", "MoveYp");
        bind(eventBuilder, "#BtnRotate", "Rotate");
        bind(eventBuilder, "#BtnZoomOut", "ZoomOut");
        bind(eventBuilder, "#BtnZoomIn", "ZoomIn");
        bind(eventBuilder, "#BtnPanXm", "PanXm");
        bind(eventBuilder, "#BtnPanXp", "PanXp");
        bind(eventBuilder, "#BtnPanZm", "PanZm");
        bind(eventBuilder, "#BtnPanZp", "PanZp");
        bind(eventBuilder, "#PlaceButton", "Place");
        bind(eventBuilder, "#CancelButton", "Cancel");
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#PlotTypeDropdown",
            EventData.of("@ConstructionId", "#PlotTypeDropdown.Value"),
            false
        );
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#BirdsEyeToggle #CheckBox",
            EventData.of("@BirdsEye", "#BirdsEyeToggle #CheckBox.Value"),
            false
        );
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#BirdsEyeDistanceSlider",
            EventData.of("@BirdsEyeDistance", "#BirdsEyeDistanceSlider.Value"),
            false
        );
        scheduleRefreshPreview(ref, store);
        BuildingPlacementDebugLog.pageBuilt(playerRef.getUsername(), 19);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        BuildingPlacementDebugLog.uiEvent(
            playerRef.getUsername(),
            data.action,
            data.birdsEye,
            data.birdsEyeDistance,
            data.constructionId
        );
        if (data.birdsEye != null) {
            birdsEyeEnabled = data.birdsEye;
            smoothPanGeneration++;
            if (birdsEyeEnabled) {
                session.resetBirdsEyePan();
                session.clearBirdsEyeSnapshot();
            } else {
                session.clearBirdsEyeSnapshot();
            }
            birdsEyeDistance =
                Math.max(
                    BuildingPlacementCameraUtil.MIN_DISTANCE,
                    Math.min(BuildingPlacementCameraUtil.MAX_DISTANCE, birdsEyeDistance)
                );
            scheduleApplyCameraAndRebuild(ref, store);
            return;
        }
        if (data.birdsEyeDistance != null) {
            birdsEyeDistance =
                Math.max(
                    BuildingPlacementCameraUtil.MIN_DISTANCE,
                    Math.min(BuildingPlacementCameraUtil.MAX_DISTANCE, data.birdsEyeDistance)
                );
            if (birdsEyeEnabled) {
                scheduleApplyCamera(ref, store);
            } else {
                scheduleRebuild(ref, store);
            }
            return;
        }
        if (data.action == null) {
            return;
        }
        float yawRad = BuildingPlacementNudgeUtil.getPlayerYawRadians(ref, store);
        switch (data.action) {
            case "MoveXm" ->
                BuildingPlacementNudgeUtil.nudgeHorizontal(
                    session, birdsEyeEnabled, yawRad, BuildingPlacementNudgeUtil.Horizontal.NEG_X
                );
            case "MoveXp" ->
                BuildingPlacementNudgeUtil.nudgeHorizontal(
                    session, birdsEyeEnabled, yawRad, BuildingPlacementNudgeUtil.Horizontal.POS_X
                );
            case "MoveZm" ->
                BuildingPlacementNudgeUtil.nudgeHorizontal(
                    session, birdsEyeEnabled, yawRad, BuildingPlacementNudgeUtil.Horizontal.NEG_Z
                );
            case "MoveZp" ->
                BuildingPlacementNudgeUtil.nudgeHorizontal(
                    session, birdsEyeEnabled, yawRad, BuildingPlacementNudgeUtil.Horizontal.POS_Z
                );
            case "MoveYm" -> session.nudge(0, -1, 0);
            case "MoveYp" -> session.nudge(0, 1, 0);
            case "Rotate" -> applyRotate();
            case "PanXm" -> pan(ref, store, -BuildingPlacementCameraUtil.PAN_STEP, 0.0);
            case "PanXp" -> pan(ref, store, BuildingPlacementCameraUtil.PAN_STEP, 0.0);
            case "PanZm" -> pan(ref, store, 0.0, -BuildingPlacementCameraUtil.PAN_STEP);
            case "PanZp" -> pan(ref, store, 0.0, BuildingPlacementCameraUtil.PAN_STEP);
            case "ZoomOut" -> {
                birdsEyeDistance =
                    Math.min(BuildingPlacementCameraUtil.MAX_DISTANCE, birdsEyeDistance + BuildingPlacementCameraUtil.DISTANCE_STEP);
                scheduleApplyCameraAndRebuild(ref, store);
                return;
            }
            case "ZoomIn" -> {
                birdsEyeDistance =
                    Math.max(BuildingPlacementCameraUtil.MIN_DISTANCE, birdsEyeDistance - BuildingPlacementCameraUtil.DISTANCE_STEP);
                scheduleApplyCameraAndRebuild(ref, store);
                return;
            }
            case "Cancel" -> {
                scheduleCancel(ref, store);
                return;
            }
            case "Place" -> {
                schedulePlace(ref, store);
                return;
            }
            case "FindValidPosition" -> {
                var plugin=EterniaModPlugin.get();var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(store.getExternalData().getWorld(),plugin).getPlot(session.getPlotId());var def=plugin.getBuildingCatalog().get(session.getBuildingId());
                if(plot!=null&&def!=null&&!BuildingPlacementValidator.findValidPosition(store.getExternalData().getWorld(),plot,playerRef.getUuid(),session,def,plugin))playerRef.sendMessage(Message.raw("No valid position for this orientation. Try rotating the house; it needs five clear blocks from plot borders and road edges."));
                scheduleApplyCameraAndRebuild(ref,store);return;
            }
            case "SnapToLocation" -> {
                BuildingPlacementSnapUtil.snapSessionToPlayer(session, ref, store);
                if (birdsEyeEnabled) {
                    session.clearBirdsEyeSnapshot();
                    captureBirdsEyeSnapshot(ref, store);
                    scheduleApplyCameraAndRebuild(ref, store);
                } else {
                    scheduleRebuild(ref, store);
                }
                return;
            }
            default -> {
                return;
            }
        }
        scheduleRebuild(ref, store);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        super.onDismiss(ref, store);
        World world = store.getExternalData().getWorld();
        world.execute(
            () -> {
                if (!ref.isValid()) {
                    return;
                }
                smoothPanGeneration++;
                if (pr != null) {
                    BuildingPlacementCameraUtil.resetToPlayerCamera(pr);
                }
            }
        );
    }

    private void applyRotate() {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        BuildingDefinition def = plugin != null ? plugin.getBuildingCatalog().get(session.getBuildingId()) : null;
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
        BuildingPlacementRotationUtil.rotateClockwise90PreservingFootprintCenter(session, def, buf);
    }

    private void pan(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, double dx, double dz) {
        if (!birdsEyeEnabled) {
            scheduleRebuild(ref, store);
            return;
        }
        session.addBirdsEyePan(dx, dz);
        scheduleApplyCamera(ref, store);
    }

    private void scheduleApplyCamera(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        store.getExternalData().getWorld().execute(
            () -> {
                if (!ref.isValid() || isDismissed()) {
                    return;
                }
                applyBirdsEyeCameraPacket(ref, store);
            }
        );
    }

    private void scheduleApplyCameraAndRebuild(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        store.getExternalData().getWorld().execute(
            () -> {
                if (!ref.isValid() || isDismissed()) {
                    return;
                }
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                if (pr != null) {
                    if (birdsEyeEnabled) {
                        applyBirdsEyeCameraPacket(ref, store);
                    } else {
                        BuildingPlacementCameraUtil.resetToPlayerCamera(pr);
                    }
                }
                rebuild();
            }
        );
    }

    private void applyBirdsEyeCameraPacket(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (!birdsEyeEnabled) {
            return;
        }
        if (!session.hasBirdsEyeSnapshot()) {
            captureBirdsEyeSnapshot(ref, store);
        }
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
        if (pr == null) {
            return;
        }
        if (tc == null) {
            BuildingPlacementCameraUtil.resetToPlayerCamera(pr);
            return;
        }
        Vector3d p = tc.getPosition();
        double fx;
        double fy;
        double fz;
        if (session.hasBirdsEyeSnapshot()) {
            fx = session.getBirdsEyeSnapshotX();
            fy = session.getBirdsEyeSnapshotY();
            fz = session.getBirdsEyeSnapshotZ();
        } else {
            Vector3i a = session.getAnchor();
            fx = a.x + 0.5;
            fy = a.y + 0.5;
            fz = a.z + 0.5;
        }
        fx += session.getBirdsEyePanX();
        fz += session.getBirdsEyePanZ();
        BuildingPlacementCameraUtil.applyBirdsEye(pr, birdsEyeDistance, p.x, p.y, p.z, fx, fy, fz);
    }

    private void captureBirdsEyeSnapshot(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        EterniaModPlugin plugin = EterniaModPlugin.get();
        Vector3i anchor = session.getAnchor();
        if (plugin == null) {
            session.setBirdsEyeSnapshot(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5);
            return;
        }
        BuildingDefinition def = plugin.getBuildingCatalog().get(session.getBuildingId());
        if (def == null) {
            session.setBirdsEyeSnapshot(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5);
            return;
        }
        Path prefabPath = PrefabResolveUtil.resolvePrefabPath(def.getPrefabPath());
        if (prefabPath == null) {
            session.setBirdsEyeSnapshot(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5);
            return;
        }
        IPrefabBuffer buf = PrefabBufferUtil.getCached(prefabPath);
        Vector3i prefabOrigin = def.resolvePrefabAnchorWorld(anchor, session.getPrefabYaw());
        HubPlotFootprint fp = PlotFootprintUtil.computeFootprint(prefabOrigin, session.getPrefabYaw(), buf);
        session.setBirdsEyeSnapshot(
            (fp.getMinX() + fp.getMaxX() + 1) / 2.0,
            (fp.getMinY() + fp.getMaxY() + 1) / 2.0,
            (fp.getMinZ() + fp.getMaxZ() + 1) / 2.0
        );
    }

    private void scheduleRefreshPreview(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
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

    private void scheduleRebuild(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        store.getExternalData().getWorld().execute(
            () -> {
                if (ref.isValid()) {
                    rebuild();
                }
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
                    if (birdsEyeEnabled) {
                        BuildingPlacementCameraUtil.resetToPlayerCamera(pr);
                    }
                    BuildingPlacementOpenHelper.cancelActive(pr, uc.getUuid());
                } else if (pr != null) {
                    clearPreview(pr);
                }
                returnOrClose(ref,store);
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
                    if (pr != null) {
                        if (birdsEyeEnabled) {
                            BuildingPlacementCameraUtil.resetToPlayerCamera(pr);
                        }
                        if (uc != null) {
                            BuildingPlacementOpenHelper.cancelActive(pr, uc.getUuid());
                        } else {
                            clearPreview(pr);
                        }
                    }
                    close();
                } else {
                    rebuild();
                }
            }
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
        BuildingDefinition def = plugin.getBuildingCatalog().get(session.getBuildingId());
        if (def == null) {
            sendError(store, ref, Message.translation("eterniamod_common.eterniamod.common.unknownBuilding"));
            return false;
        }
        String errKey =
            BuildingPlacementValidator.validate(
                world, plotManager, plot, uc.getUuid(), session.getAnchor(), session.getPrefabYaw(), def, plugin
            );
        if (errKey != null) {
            sendError(store, ref, Message.translation("eterniamod_common.eterniamod.common." + errKey));
            return false;
        }
        if (!BuildingPlacementCommit.place(ref, store, session, plugin)) {
            sendError(store, ref, Message.translation("eterniamod_common.eterniamod.common.placeFailed"));
            return false;
        }
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (pr != null) {
            pr.sendMessage(Message.translation("eterniamod_common.eterniamod.common.buildingPlaced"));
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
        BuildingDefinition def = plugin.getBuildingCatalog().get(session.getBuildingId());
        if (def == null) {
            clearClientPrefabPreview(pr);
            BuildingPlacementWireframeOverlay.clearFor(pr);
            BuildingPlacementDebugLog.previewResult(pr.getUsername(), session.getBuildingId(), false, "unknown building");
            return;
        }
        IPrefabBuffer buf = PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath());
        if (buf == null) {
            clearClientPrefabPreview(pr);
            BuildingPlacementWireframeOverlay.clearFor(pr);
            BuildingPlacementDebugLog.previewResult(
                pr.getUsername(),
                def.getPrefabPath(),
                false,
                "prefab buffer unresolved"
            );
            return;
        }
        UUIDComponent uc = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uc == null) {
            clearClientPrefabPreview(pr);
            BuildingPlacementWireframeOverlay.clearFor(pr);
            return;
        }
        World world = store.getExternalData().getWorld();
        HubPlotManager plotManager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        HubPlotRecord plot = plotManager.getPlot(session.getPlotId());
        if (plot == null) {
            clearClientPrefabPreview(pr);
            BuildingPlacementWireframeOverlay.clearFor(pr);
            return;
        }
        String errKey =
            BuildingPlacementValidator.validate(
                world, plotManager, plot, uc.getUuid(), session.getAnchor(), session.getPrefabYaw(), def, plugin
            );
        boolean valid = errKey == null;
        Vector3i prefabOrigin = def.resolvePrefabAnchorWorld(session.getAnchor(), session.getPrefabYaw());
        HubPlotFootprint buildingFp = PlotFootprintUtil.computeFootprint(prefabOrigin, session.getPrefabYaw(), buf);
        syncClientPrefabPreview(pr, def, prefabOrigin);
        BuildingPlacementWireframeOverlay.send(pr, plot.getFootprint(), buildingFp, valid);
        BuildingPlacementDebugLog.previewResult(
            pr.getUsername(),
            def.getPrefabPath(),
            true,
            "valid=" + valid + " origin=" + prefabOrigin
        );
    }

    private void syncClientPrefabPreview(
        @Nonnull PlayerRef pr,
        @Nonnull BuildingDefinition def,
        @Nonnull Vector3i prefabOriginWorld
    ) {
        boolean needFull =
            !clientPrefabPreviewActive
                || !session.getBuildingId().equals(lastPreviewBuildingId)
                || session.getRotationSteps() != lastPreviewRotationSteps;
        if (needFull) {
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
                BuildingPlacementDebugLog.previewResult(
                    pr.getUsername(),
                    def.getPrefabPath(),
                    false,
                    "sendFull returned false"
                );
                return;
            }
            clientPrefabPreviewActive = true;
            lastPreviewBuildingId = session.getBuildingId();
            lastPreviewRotationSteps = session.getRotationSteps();
            lastPreviewOriginFloored =
                BuildingPlacementClientPrefabPreview.flooredClientPreviewOrigin(
                    prefabOriginWorld,
                    session,
                    session.getPrefabYaw()
                );
            return;
        }
        BuildingPlacementClientPrefabPreview.Payload payload = session.getClientPrefabPreviewPayload();
        Vector3i floored =
            BuildingPlacementClientPrefabPreview.flooredClientPreviewOrigin(
                prefabOriginWorld,
                session,
                session.getPrefabYaw()
            );
        if (lastPreviewOriginFloored != null && lastPreviewOriginFloored.equals(floored)) {
            return;
        }
        if (payload != null) {
            BuildingPlacementClientPrefabPreview.sendPositionOnly(pr, prefabOriginWorld, payload, session.getPrefabYaw());
        }
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
        lastPreviewBuildingId = null;
        lastPreviewRotationSteps = -1;
        lastPreviewOriginFloored = null;
    }

    private void clearPreview(@Nullable PlayerRef pr) {
        clearClientPrefabPreview(pr);
        BuildingPlacementWireframeOverlay.clearFor(pr);
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
                .append(
                    new KeyedCodec<>("@ConstructionId", Codec.STRING),
                    (d, v) -> d.constructionId = v,
                    d -> d.constructionId
                )
                .add()
                .append(new KeyedCodec<>("@BirdsEye", Codec.BOOLEAN), (d, v) -> d.birdsEye = v, d -> d.birdsEye)
                .add()
                .append(
                    new KeyedCodec<>("@BirdsEyeDistance", Codec.FLOAT),
                    (d, v) -> d.birdsEyeDistance = v,
                    d -> d.birdsEyeDistance
                )
                .add()
                .build();

        @Nullable
        public String action;

        @Nullable
        public String constructionId;

        @Nullable
        public Boolean birdsEye;

        @Nullable
        public Float birdsEyeDistance;
    }
}
