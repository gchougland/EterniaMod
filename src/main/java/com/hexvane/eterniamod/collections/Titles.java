package com.hexvane.eterniamod.collections;

import com.hexvane.eterniamod.domain.CollectionService;
import com.hypixel.hytale.server.core.Message;

/**
 * Player text for titles lives in {@code eternia_titles.lang}, keyed by the part of the content id after
 * {@code eternia:title/}: {@code <slug>.name} matches the definition's name and {@code <slug>.description}
 * says how the title is earned. The nameplate itself still uses the definition's {@code assetId}.
 */
public final class Titles {
    public static final String PREFIX = "eternia:title/";
    public static final String LANG = "eternia_titles";
    /** Longest suffix or prefix text, so a Collection card and a nameplate both stay readable. */
    public static final int MAX_TEXT = 24;

    private Titles() {}

    public static boolean isTitle(CollectionService.Kind kind) {
        return kind == CollectionService.Kind.PREFIX_TITLE || kind == CollectionService.Kind.SUFFIX_TITLE;
    }

    /** The lang key stem for a title id, or null when the id is not a title. */
    public static String slug(String contentId) {
        if (contentId == null || !contentId.startsWith(PREFIX)) return null;
        String slug = contentId.substring(PREFIX.length());
        return slug.matches("[a-z0-9_]+") ? slug : null;
    }

    public static String descriptionKey(String contentId) {
        String slug = slug(contentId);
        return slug == null ? null : LANG + "." + slug + ".description";
    }

    /** How the title is earned, for a card detail or a store line; empty for anything that is not a title. */
    public static Message description(String contentId) {
        String key = descriptionKey(contentId);
        return key == null ? Message.raw("") : Message.translation(key);
    }
}
