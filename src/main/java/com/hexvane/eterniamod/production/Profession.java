package com.hexvane.eterniamod.production;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One profession, read from {@code Server/EterniaMod/Production/Professions/<Id>.json}: its station, its six goods,
 * tools, talent cards, rare finds and where the station's moving parts sit. Content lives in the file, not in Java.
 */
public record Profession(String id, String name, String station, String unit, double cycleSeconds, String quirk,
                         List<Good> goods, List<String> tools, List<String> toolItems, List<String> upgradeItems, List<Card> cards, Map<String, Map<String, Double>> sets,
                         List<Find> finds, Layout layout) {

    public static final List<String> PATHS = List.of("bounty", "fortune", "vigor");

    /** A good of one tier. {@code item} is the vanilla item thrown out when it is collected. */
    public record Good(String name, String item) {}

    /** A talent card. {@code milestone} is 1 for the first milestone, and so on. */
    public record Card(String id, String path, int milestone, String name, String text, Map<String, Double> effects) {}

    public record Find(String id, String name, String item, String text) {}

    /**
     * The station in the world, in blocks from its anchor (the middle of the pit mouth, at ground level), before it is
     * turned. {@code prefabs} holds one prefab path per tier.
     */
    public record Layout(List<String> prefabs, int radius, int depth, int height, double[] collect, double[] board,
                         double[] heap, double[] lift, double liftLow, double liftHigh, String liftBlock,
                         double[] cartFrom, double[] cartTo, String cartBlock, String workSound, String workParticles) {}

    public Good good(int tier) { return goods.get(Math.clamp(tier, 1, goods.size()) - 1); }

    /** The vanilla item pictured on the upgrade button at a tier. */
    public String upgradeItem(int tier) { return upgradeItems.get(Math.clamp(tier, 1, upgradeItems.size()) - 1); }

    public String prefab(int tier) { return layout.prefabs().get(Math.clamp(tier, 1, layout.prefabs().size()) - 1); }

    /** The prefab file for a tier. {@code shallow} picks the twin with no pit, for ground one block thick. */
    public String prefabFile(int tier, boolean shallow) { return prefab(tier) + (shallow ? "_Shallow" : "") + ".prefab.json"; }

    /** The three cards dealt at a milestone, one per path, in path order. */
    public List<Card> deal(int milestone) {
        var out = new ArrayList<Card>();
        for (String path : PATHS) for (Card card : cards) if (card.milestone() == milestone && card.path().equals(path)) out.add(card);
        return out;
    }

    public Card card(String cardId) {
        for (Card card : cards) if (card.id().equals(cardId)) return card;
        return null;
    }

    public Find find(String findId) {
        for (Find find : finds) if (find.id().equals(findId)) return find;
        return null;
    }

    public static String resource(String id) { return "Server/EterniaMod/Production/Professions/" + id + ".json"; }

    public static Profession load(ClassLoader loader, String fileId) {
        try (var input = loader.getResourceAsStream(resource(fileId))) {
            if (input == null) throw new IllegalStateException("Missing profession file " + resource(fileId));
            return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new IllegalStateException("Unreadable profession file " + resource(fileId), unreadable);
        }
    }

    static Profession parse(Reader reader) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        var goods = new ArrayList<Good>();
        for (JsonElement e : json.getAsJsonArray("Goods")) {
            JsonObject o = e.getAsJsonObject();
            goods.add(new Good(o.get("Name").getAsString(), o.get("Item").getAsString()));
        }
        var tools = new ArrayList<String>();
        for (JsonElement e : json.getAsJsonArray("Tools")) tools.add(e.getAsString());
        var toolItems = new ArrayList<String>();
        for (JsonElement e : json.getAsJsonArray("ToolItems")) toolItems.add(e.getAsString());
        var upgradeItems = new ArrayList<String>();
        for (JsonElement e : json.getAsJsonArray("UpgradeItems")) upgradeItems.add(e.getAsString());
        var cards = new ArrayList<Card>();
        for (JsonElement e : json.getAsJsonArray("Cards")) {
            JsonObject o = e.getAsJsonObject();
            cards.add(new Card(o.get("Id").getAsString(), o.get("Path").getAsString(), o.get("Milestone").getAsInt(),
                o.get("Name").getAsString(), o.get("Text").getAsString(), numbers(o.getAsJsonObject("Effects"))));
        }
        var sets = new LinkedHashMap<String, Map<String, Double>>();
        for (var e : json.getAsJsonObject("Sets").entrySet()) sets.put(e.getKey(), numbers(e.getValue().getAsJsonObject()));
        var finds = new ArrayList<Find>();
        for (JsonElement e : json.getAsJsonArray("Finds")) {
            JsonObject o = e.getAsJsonObject();
            finds.add(new Find(o.get("Id").getAsString(), o.get("Name").getAsString(), o.get("Item").getAsString(), o.get("Text").getAsString()));
        }
        JsonObject l = json.getAsJsonObject("Station");
        var prefabs = new ArrayList<String>();
        for (JsonElement e : l.getAsJsonArray("Prefabs")) prefabs.add(e.getAsString());
        var layout = new Layout(prefabs, l.get("Radius").getAsInt(), l.get("Depth").getAsInt(), l.get("Height").getAsInt(),
            point(l, "Collect"), point(l, "Board"), point(l, "Heap"), point(l, "Lift"), l.get("LiftLow").getAsDouble(),
            l.get("LiftHigh").getAsDouble(), l.get("LiftBlock").getAsString(), point(l, "CartFrom"), point(l, "CartTo"),
            l.get("CartBlock").getAsString(), text(l, "WorkSound"), text(l, "WorkParticles"));
        if (tools.size() != 4 || toolItems.size() != 4) throw new IllegalStateException("A profession needs exactly 4 tools, each with a picture");
        if (upgradeItems.isEmpty()) throw new IllegalStateException("A profession needs a picture for its upgrade button");
        return new Profession(json.get("Id").getAsString(), json.get("Name").getAsString(), json.get("Station").getAsJsonObject().get("Name").getAsString(),
            json.get("Unit").getAsString(), json.get("CycleSeconds").getAsDouble(), json.get("Quirk").getAsString(),
            List.copyOf(goods), List.copyOf(tools), List.copyOf(toolItems), List.copyOf(upgradeItems), List.copyOf(cards), sets, List.copyOf(finds), layout);
    }

    private static String text(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsString() : null; }

    private static double[] point(JsonObject o, String key) {
        JsonArray a = o.getAsJsonArray(key);
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    private static Map<String, Double> numbers(JsonObject o) {
        var out = new LinkedHashMap<String, Double>();
        if (o != null) for (var e : o.entrySet()) out.put(e.getKey(), e.getValue().getAsDouble());
        return out;
    }
}
