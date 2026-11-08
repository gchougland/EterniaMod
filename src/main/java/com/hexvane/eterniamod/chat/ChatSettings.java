package com.hexvane.eterniamod.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Chat tuning from {@code Server/EterniaMod/Chat/ChatSettings.json}. Bad or missing values fall back to safe defaults. */
public record ChatSettings(ChatChannel defaultChannel, double nearbyRadius) {
    public static final String RESOURCE = "Server/EterniaMod/Chat/ChatSettings.json";
    public static final ChatSettings DEFAULTS = new ChatSettings(ChatChannel.SERVER, 50);
    static final double MIN_RADIUS = 4, MAX_RADIUS = 512;

    public ChatSettings {
        if (defaultChannel == null || defaultChannel.needsGroup()) defaultChannel = ChatChannel.SERVER;
        if (!(nearbyRadius >= MIN_RADIUS && nearbyRadius <= MAX_RADIUS)) nearbyRadius = DEFAULTS.nearbyRadius;
    }

    /** Whole blocks, for player text. */
    public int nearbyBlocks() { return (int) Math.round(nearbyRadius); }

    public static ChatSettings load(ClassLoader loader) {
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) return DEFAULTS;
            return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return DEFAULTS;
        }
    }

    static ChatSettings parse(Reader reader) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        ChatChannel channel = json.has("DefaultChannel")
            ? ChatChannel.parse(json.get("DefaultChannel").getAsString()).orElse(DEFAULTS.defaultChannel) : DEFAULTS.defaultChannel;
        double radius = json.has("NearbyRadius") ? json.get("NearbyRadius").getAsDouble() : DEFAULTS.nearbyRadius;
        return new ChatSettings(channel, radius);
    }
}
