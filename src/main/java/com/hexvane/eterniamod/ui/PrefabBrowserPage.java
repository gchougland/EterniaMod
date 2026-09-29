package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.BuildingCatalog;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.building.BuildingItemMetadata;
import com.hexvane.eterniamod.prefab.PrefabDefinitionFactory;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.prop.PropCatalog;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hexvane.eterniamod.prop.PropItemMetadata;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.builtin.buildertools.prefabeditor.PrefabEditSessionManager;
import com.hypixel.hytale.builtin.buildertools.prefablist.AssetPrefabFileProvider;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.ui.browser.FileBrowserConfig;
import com.hypixel.hytale.server.core.ui.browser.FileBrowserEventData;
import com.hypixel.hytale.server.core.ui.browser.ServerFileBrowser;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PrefabBrowserPage extends EterniaInteractiveCustomUIPage<PrefabBrowserPage.PageData> {
    private static final String MSG_UI = "eterniamod_ui.eterniamod.ui.prefabbrowser";
    private static final String BASE_PACK_KEY = "HytaleAssets";
    private static final int PREVIEW_TILT = 23;
    private static final int PREVIEW_SPIN_SPEED = 27;
    private static final int PREVIEW_MAX_SIZE = 100;
    private static final int DEFAULT_BIOME_TINT =
        ColorParseUtil.colorToARGBInt(PrefabEditSessionManager.DEFAULT_TINT) & 0x00FFFFFF;
    private static final int DEFAULT_WATER_TINT =
        ColorParseUtil.colorToARGBInt(Environment.getUnknownFor("").getWaterTint()) & 0x00FFFFFF;

    @Nonnull
    private final ServerFileBrowser browser;
    @Nonnull
    private final AssetPrefabFileProvider assetProvider;
    @Nonnull
    private Path assetsCurrentDir;

    @Nullable
    private Path previewedPath;
    @Nullable
    private String previewedFileName;
    @Nullable
    private String previewedVirtualPath;
    private boolean previewHasManagementBlock;
    @Nullable
    private String statusMessageKey;

    public PrefabBrowserPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, PageData.CODEC);
        assetProvider = new AssetPrefabFileProvider();
        boolean multiplePacks = PrefabStore.get().getAllBrowsablePrefabPaths().size() > 1;
        assetsCurrentDir = multiplePacks ? Paths.get("") : Paths.get(BASE_PACK_KEY);
        FileBrowserConfig config =
            FileBrowserConfig.builder()
                .listElementId("#FileList")
                .searchInputId("#SearchInput")
                .roots(List.of())
                .allowedExtensions(".prefab.json")
                .enableRootSelector(false)
                .enableSearch(true)
                .enableDirectoryNav(true)
                .maxResults(50)
                .build();
        browser = new ServerFileBrowser(config, Paths.get(BASE_PACK_KEY), null);
    }

    public static void applyLocalization(@Nonnull UICommandBuilder commandBuilder) {
        String p = MSG_UI;
        commandBuilder.set("#BrowserTitle.TextSpans", Message.translation(p + ".title"));
        commandBuilder.set("#PreviewTitle.TextSpans", Message.translation(p + ".previewTitle"));
        commandBuilder.set("#CreatePropButton.TextSpans", Message.translation(p + ".createProp"));
        commandBuilder.set("#CreateBuildingButton.TextSpans", Message.translation(p + ".createBuilding"));
    }

    @Override
    public void build(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull UICommandBuilder commandBuilder,
        @Nonnull UIEventBuilder eventBuilder,
        @Nonnull Store<EntityStore> store
    ) {
        commandBuilder.append("EterniaMod/PrefabBrowserPage.ui");bindHome(eventBuilder);
        applyLocalization(commandBuilder);
        commandBuilder.set("#LoadButton.Visible", false);
        browser.buildSearchInput(commandBuilder, eventBuilder);
        buildPersistentBindings(eventBuilder);
        buildCurrentPath(commandBuilder);
        buildFileList(commandBuilder, eventBuilder);
        buildActionButtons(commandBuilder, eventBuilder);
        buildStatus(commandBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        if (data.action != null) {
            switch (data.action) {
                case "CreateProp" -> handleCreateProp(ref, store);
                case "CreateBuilding" -> handleCreateBuilding(ref, store);
            }
            return;
        }
        if (data.searchQuery != null) {
            browser.setSearchQuery(data.searchQuery);
            sendListingUpdate();
            return;
        }
        if (data.preview != null) {
            handlePreviewRequest(data.preview);
            return;
        }
        String selectedPath = data.searchResult != null ? data.searchResult : data.file;
        if (selectedPath != null) {
            handleAssetsNavigation(selectedPath, data.searchResult != null);
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        clearPreview();
    }

    private void handleCreateProp(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (previewedPath == null || previewedVirtualPath == null) {
            return;
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (plugin == null) {
            return;
        }
        IPrefabBuffer buffer = PrefabResolveUtil.resolvePrefabBuffer(
            PrefabDefinitionFactory.prefabPathKeyFromResolved(previewedPath)
        );
        if (buffer == null) {
            setStatus("eterniamod_common.eterniamod.common.prefabNotLoadable");
            sendActionUpdate();
            return;
        }
        if (!PrefabDefinitionFactory.validatePropPrefab(buffer)) {
            setStatus("eterniamod_common.eterniamod.common.prefabNotLoadable");
            sendActionUpdate();
            return;
        }
        PropDefinition def = PrefabDefinitionFactory.buildPropDefinition(previewedVirtualPath, previewedPath);
        PropCatalog catalog = plugin.getPropCatalog();
        if (catalog.contains(def.getId())) {
            setStatus("eterniamod_common.eterniamod.common.duplicateDefinition");
            sendActionUpdate();
            return;
        }
        try {
            catalog.persist(def);
            catalog.register(def);
        } catch (IOException e) {
            setStatus("eterniamod_common.eterniamod.common.definitionSaveFailed");
            sendActionUpdate();
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        ItemStack stack =
            PropItemMetadata.withProp(
                new ItemStack(EterniaModConstants.PROP_ITEM_ID, 1),
                def.getId(),
                def.getDisplayName(),
                playerRef.getLanguage()
            );
        player.giveItem(stack, ref, store);
        playerRef.sendMessage(
            Message.translation("eterniamod_commands.commands.eternia.prefab.createProp.success")
                .param("prop", def.getDisplayName() != null ? def.getDisplayName() : def.getId())
        );
        statusMessageKey = null;
        close();
    }

    private void handleCreateBuilding(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (previewedPath == null || previewedVirtualPath == null || !previewHasManagementBlock) {
            setStatus("eterniamod_common.eterniamod.common.missingManagementBlock");
            sendActionUpdate();
            return;
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (plugin == null) {
            return;
        }
        IPrefabBuffer buffer = PrefabResolveUtil.resolvePrefabBuffer(
            PrefabDefinitionFactory.prefabPathKeyFromResolved(previewedPath)
        );
        if (buffer == null) {
            setStatus("eterniamod_common.eterniamod.common.prefabNotLoadable");
            sendActionUpdate();
            return;
        }
        BuildingDefinition def =
            PrefabDefinitionFactory.buildBuildingDefinition(previewedVirtualPath, previewedPath, buffer);
        if (def == null) {
            setStatus("eterniamod_common.eterniamod.common.missingManagementBlock");
            sendActionUpdate();
            return;
        }
        BuildingCatalog catalog = plugin.getBuildingCatalog();
        if (catalog.contains(def.getId())) {
            setStatus("eterniamod_common.eterniamod.common.duplicateDefinition");
            sendActionUpdate();
            return;
        }
        try {
            catalog.persist(def);
            catalog.register(def);
        } catch (IOException e) {
            setStatus("eterniamod_common.eterniamod.common.definitionSaveFailed");
            sendActionUpdate();
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        ItemStack stack =
            BuildingItemMetadata.withBuilding(
                new ItemStack(EterniaModConstants.BUILDING_ITEM_ID, 1),
                def.getId(),
                def.getDisplayName(),
                playerRef.getLanguage()
            );
        player.giveItem(stack, ref, store);
        playerRef.sendMessage(
            Message.translation("eterniamod_commands.commands.eternia.prefab.createBuilding.success")
                .param("building", def.getDisplayName() != null ? def.getDisplayName() : def.getId())
        );
        statusMessageKey = null;
        close();
    }

    private void handlePreviewRequest(@Nonnull String selectedPath) {
        Path resolvedFile = resolvePreviewPath(selectedPath);
        if (resolvedFile == null) {
            sendUpdate();
            return;
        }
        if (resolvedFile.equals(previewedPath)) {
            sendUpdate();
            return;
        }
        previewedPath = resolvedFile;
        previewedFileName = selectedPath;
        previewedVirtualPath = virtualPathFor(selectedPath);
        statusMessageKey = null;
        IPrefabBuffer buffer =
            PrefabResolveUtil.resolvePrefabBuffer(PrefabDefinitionFactory.prefabPathKeyFromResolved(resolvedFile));
        previewHasManagementBlock = buffer != null && PrefabDefinitionFactory.findManagementBlockLocalPos(buffer) != null;
        if (!previewHasManagementBlock) {
            statusMessageKey = "eterniamod_common.eterniamod.common.missingManagementBlock";
        } else {
            statusMessageKey = null;
        }
        BlockSelection selection = PrefabStore.get().getPrefab(resolvedFile);
        sendPreviewPacket(selection);
        sendActionUpdate();
    }

    private void handleAssetsNavigation(@Nonnull String selectedPath, boolean isSearchResult) {
        if ("~".equals(selectedPath)) {
            boolean multiplePacks = PrefabStore.get().getAllBrowsablePrefabPaths().size() > 1;
            assetsCurrentDir = multiplePacks ? Paths.get("") : Paths.get(BASE_PACK_KEY);
            clearSelection();
            sendListingUpdate();
            return;
        }
        if ("..".equals(selectedPath)) {
            if (assetsCurrentDir.getNameCount() > 1) {
                assetsCurrentDir = assetsCurrentDir.getParent();
            } else if (assetsCurrentDir.getNameCount() == 1) {
                assetsCurrentDir = Paths.get("");
            }
            clearSelection();
            sendListingUpdate();
            return;
        }
        String targetVirtualPath =
            isSearchResult
                ? selectedPath
                : assetsCurrentDir.toString().isEmpty()
                    ? selectedPath
                    : assetsCurrentDir.toString().replace('\\', '/') + "/" + selectedPath;
        Path resolvedPath = assetProvider.resolveVirtualPath(targetVirtualPath);
        if (resolvedPath == null) {
            sendUpdate();
            return;
        }
        if (Files.isDirectory(resolvedPath)) {
            assetsCurrentDir = Paths.get(targetVirtualPath);
            clearSelection();
            sendListingUpdate();
        } else {
            handlePreviewRequest(selectedPath);
        }
    }

    private void clearSelection() {
        previewedPath = null;
        previewedFileName = null;
        previewedVirtualPath = null;
        previewHasManagementBlock = false;
        statusMessageKey = null;
        sendPreviewPacket(null);
    }

    private void setStatus(@Nonnull String messageKey) {
        statusMessageKey = messageKey;
    }

    private void buildPersistentBindings(@Nonnull UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#HomeButton",
            EventData.of(FileBrowserEventData.KEY_FILE, "~")
        );
    }

    private void buildCurrentPath(@Nonnull UICommandBuilder commandBuilder) {
        String currentDirStr = assetsCurrentDir.toString().replace('\\', '/');
        String[] parts = currentDirStr.split("/", 2);
        String displayPath = parts.length > 1 ? parts[1] : "/";
        commandBuilder.set("#CurrentPath.Text", displayPath);
    }

    private void buildFileList(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#FileList");
        var entries = assetProvider.getFiles(assetsCurrentDir, browser.getSearchQuery());
        int buttonIndex = 0;
        boolean canGoUp = assetsCurrentDir.getNameCount() >= 1 && !assetsCurrentDir.toString().isEmpty();
        if (canGoUp && browser.getSearchQuery().isEmpty()) {
            commandBuilder.append("#FileList", "EterniaMod/PrefabFileButton.ui");
            commandBuilder.set("#FileList[0].Text", "Parent folder");
            eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#FileList[0]",
                EventData.of(FileBrowserEventData.KEY_FILE, "..")
            );
            buttonIndex++;
        }
        for (var entry : entries) {
            String displayText = entry.isDirectory() ? entry.displayName() + "/" : entry.displayName();
            String selector = "#FileList[" + buttonIndex + "]";
            commandBuilder.append("#FileList", "EterniaMod/PrefabFileButton.ui");
            boolean isFile = !entry.isDirectory();
            boolean isActive=isFile&&entry.name().equals(previewedFileName);
            String caption=(isActive?"• ":"")+displayText;
            commandBuilder.set(selector + ".Text", caption);
            commandBuilder.setObject(selector+".Anchor",UiAnchors.heightWithBottom(Math.max(44,UiPresentation.wrappedHeight(caption,520,24)+20),6));
            String fileEventKey =
                !browser.getSearchQuery().isEmpty() && isFile
                    ? FileBrowserEventData.KEY_SEARCH_RESULT
                    : FileBrowserEventData.KEY_FILE;
            if (isFile) {
                eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    selector,
                    EventData.of(FileBrowserEventData.KEY_PREVIEW, entry.name()),
                    false
                );
            } else {
                eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    selector,
                    EventData.of(fileEventKey, entry.name())
                );
            }
            buttonIndex++;
        }
    }

    private void buildActionButtons(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        boolean hasPreview = previewedPath != null;
        commandBuilder.set("#CreatePropButton.Visible", hasPreview);
        commandBuilder.set("#CreateBuildingButton.Visible", hasPreview);
        commandBuilder.set("#CreateBuildingButton.Disabled", !previewHasManagementBlock);
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#CreatePropButton",
            new EventData().append("Action", "CreateProp"),
            false
        );
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#CreateBuildingButton",
            new EventData().append("Action", "CreateBuilding"),
            false
        );
    }

    private void buildStatus(@Nonnull UICommandBuilder commandBuilder) {
        if (statusMessageKey != null) {
            commandBuilder.set("#StatusText.Visible", true);
            commandBuilder.set("#StatusText.TextSpans", Message.translation(statusMessageKey));
        } else {
            commandBuilder.set("#StatusText.Visible", false);
        }
    }

    private void sendListingUpdate() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        buildPersistentBindings(eventBuilder);
        buildCurrentPath(commandBuilder);
        buildFileList(commandBuilder, eventBuilder);
        buildActionButtons(commandBuilder, eventBuilder);
        buildStatus(commandBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private void sendActionUpdate() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        buildPersistentBindings(eventBuilder);
        buildFileList(commandBuilder, eventBuilder);
        buildActionButtons(commandBuilder, eventBuilder);
        buildStatus(commandBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    @Nullable
    private Path resolvePreviewPath(@Nonnull String selectedPath) {
        Path resolved = assetProvider.resolveVirtualPath(virtualPathFor(selectedPath));
        return resolved != null && Files.isRegularFile(resolved) ? resolved : null;
    }

    @Nonnull
    private String virtualPathFor(@Nonnull String selectedPath) {
        return selectedPath.contains("/")
            ? selectedPath
            : assetsCurrentDir.toString().replace('\\', '/') + "/" + selectedPath;
    }

    private void sendPreviewPacket(@Nullable BlockSelection selection) {
        BuilderToolPrefabPreview packet = new BuilderToolPrefabPreview();
        if (selection != null) {
            packet.tilt = PREVIEW_TILT;
            packet.spinSpeed = PREVIEW_SPIN_SPEED;
            packet.previewScale = PREVIEW_MAX_SIZE;
            var editorPacket = selection.toPacket();
            packet.blocksChange = editorPacket.blocksChange;
            packet.fluidsChange = editorPacket.fluidsChange;
            packet.entityChanges = editorPacket.entityChanges;
            applyTintFromPlayerPosition(packet);
        }
        playerRef.getPacketHandler().write(packet);
    }

    private void applyTintFromPlayerPosition(@Nonnull BuilderToolPrefabPreview packet) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            packet.biomeTint = DEFAULT_BIOME_TINT;
            packet.waterTint = DEFAULT_WATER_TINT;
            return;
        }
        Store<EntityStore> store = ref.getStore();
        var world = store.getExternalData().getWorld();
        var pos = playerRef.getTransform().getPosition();
        int x = MathUtil.floor(pos.x);
        int y = MathUtil.floor(pos.y);
        int z = MathUtil.floor(pos.z);
        BlockChunk blockChunk = ChunkSectionBlockUtil.blockChunkAt(world, x, z);
        if (blockChunk == null) {
            packet.biomeTint = DEFAULT_BIOME_TINT;
            packet.waterTint = DEFAULT_WATER_TINT;
            return;
        }
        packet.biomeTint = blockChunk.getTint(x, z);
        int envId = blockChunk.getEnvironment(x, y, z);
        var environment = Environment.getAssetMap().getAsset(envId);
        if (environment != null) {
            var waterColor = environment.getWaterTint();
            if (waterColor != null) {
                packet.waterTint =
                    (waterColor.red & 0xFF) << 16 | (waterColor.green & 0xFF) << 8 | (waterColor.blue & 0xFF);
                return;
            }
        }
        packet.waterTint = DEFAULT_WATER_TINT;
    }

    private void clearPreview() {
        previewedPath = null;
        previewedFileName = null;
        previewedVirtualPath = null;
        previewHasManagementBlock = false;
        sendPreviewPacket(null);
    }

    public static final class PageData {
        public static final BuilderCodec<PageData> CODEC =
            BuilderCodec.builder(PageData.class, PageData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, a) -> d.action = a, d -> d.action)
                .add()
                .append(new KeyedCodec<>(FileBrowserEventData.KEY_FILE, Codec.STRING), (d, s) -> d.file = s, d -> d.file)
                .add()
                .append(
                    new KeyedCodec<>(FileBrowserEventData.KEY_SEARCH_QUERY, Codec.STRING),
                    (d, s) -> d.searchQuery = s,
                    d -> d.searchQuery
                )
                .add()
                .append(
                    new KeyedCodec<>(FileBrowserEventData.KEY_SEARCH_RESULT, Codec.STRING),
                    (d, s) -> d.searchResult = s,
                    d -> d.searchResult
                )
                .add()
                .append(
                    new KeyedCodec<>(FileBrowserEventData.KEY_PREVIEW, Codec.STRING),
                    (d, s) -> d.preview = s,
                    d -> d.preview
                )
                .add()
                .build();

        @Nullable
        public String action;
        @Nullable
        public String file;
        @Nullable
        public String searchQuery;
        @Nullable
        public String searchResult;
        @Nullable
        public String preview;
    }
}
