package com.hexvane.eterniamod.building;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.building.EterniaBsonCodecs;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonString;

public final class BuildingItemMetadata {
    public static final String BSON_KEY = "EterniaBuildingItem";
    public static final String FIELD_BUILDING_ID = "buildingId";
    public static final String FIELD_DISPLAY_NAME = "displayName";
    public static final String INSTANCE_TRANSLATION_PROPERTIES_KEY = "TranslationProperties";

    private static final String LANG_NAME = "eterniamod_items.items.Eternia_Building_Item.instance.name";
    private static final String LANG_DESC = "eterniamod_items.items.Eternia_Building_Item.instance.description";

    private BuildingItemMetadata() {}

    @Nonnull
    public static ItemStack withBuilding(@Nonnull ItemStack base, @Nonnull String buildingId, @Nullable String displayName) {
        return withBuilding(base, buildingId, displayName, null);
    }

    @Nonnull
    public static ItemStack withBuilding(
        @Nonnull ItemStack base,
        @Nonnull String buildingId,
        @Nullable String displayName,
        @Nullable String language
    ) {
        BsonDocument root = new BsonDocument();
        root.put(FIELD_BUILDING_ID, new BsonString(buildingId.trim()));
        String resolvedName = resolveBuildingLabel(displayName, buildingId);
        if (resolvedName != null && !resolvedName.isBlank()) {
            root.put(FIELD_DISPLAY_NAME, new BsonString(resolvedName.trim()));
        }
        BsonDocument meta = new BsonDocument();
        meta.put(BSON_KEY, root);
        ItemStack stack = base.withMetadata(meta);
        return applyInstanceTooltip(stack, resolvedName, language);
    }

    @Nullable
    public static String readBuildingId(@Nullable ItemStack stack) {
        BsonDocument root = readRoot(stack);
        if (root == null) {
            return null;
        }
        var v = root.get(FIELD_BUILDING_ID);
        if (v == null || !v.isString()) {
            return null;
        }
        String s = v.asString().getValue();
        return s != null && !s.isBlank() ? s.trim() : null;
    }

    @Nullable
    public static String readDisplayName(@Nullable ItemStack stack) {
        BsonDocument root = readRoot(stack);
        if (root == null) {
            return null;
        }
        var v = root.get(FIELD_DISPLAY_NAME);
        if (v == null || !v.isString()) {
            return null;
        }
        String s = v.asString().getValue();
        return s != null && !s.isBlank() ? s.trim() : null;
    }

    public static boolean matchesBuilding(@Nullable ItemStack stack, @Nonnull String buildingId) {
        String onStack = readBuildingId(stack);
        return onStack != null && onStack.equals(buildingId.trim());
    }

    @Nonnull
    private static ItemStack applyInstanceTooltip(
        @Nonnull ItemStack stack,
        @Nullable String buildingLabel,
        @Nullable String language
    ) {
        if (buildingLabel == null || buildingLabel.isBlank()) {
            return stack;
        }
        String label = buildingLabel.trim();
        String lang = language != null && !language.isBlank() ? language : "en-US";
        String namePlain = resolveLang(lang, LANG_NAME, label);
        String descPlain = resolveLang(lang, LANG_DESC, label);
        BsonDocument tp = new BsonDocument();
        tp.put("Name", new BsonString(namePlain));
        tp.put("Description", new BsonString(descPlain));
        stack = stack.withMetadata(INSTANCE_TRANSLATION_PROPERTIES_KEY, tp);
        return stack.withMetadata(
            ItemDisplayMetadata.KEYED_CODEC,
            new ItemDisplayMetadata(Message.raw(namePlain), Message.raw(descPlain))
        );
    }

    @Nonnull
    private static String resolveLang(@Nonnull String language, @Nonnull String key, @Nonnull String buildingLabel) {
        I18nModule i18n = I18nModule.get();
        String text = i18n != null ? i18n.getMessage(language, key) : null;
        if (text == null || text.isBlank()) {
            text = key;
        }
        return text.replace("{building}", buildingLabel);
    }

    @Nullable
    private static String resolveBuildingLabel(@Nullable String displayName, @Nonnull String buildingId) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        EterniaModPlugin plugin = EterniaModPlugin.get();
        if (plugin != null) {
            BuildingDefinition def = plugin.getBuildingCatalog().get(buildingId.trim());
            if (def != null && def.getDisplayName() != null && !def.getDisplayName().isBlank()) {
                return def.getDisplayName().trim();
            }
        }
        return buildingId.trim();
    }

    @Nullable
    private static BsonDocument readRoot(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return null;
        }
        return stack.getFromMetadataOrNull(BSON_KEY, EterniaBsonCodecs.BSON_DOCUMENT);
    }
}
