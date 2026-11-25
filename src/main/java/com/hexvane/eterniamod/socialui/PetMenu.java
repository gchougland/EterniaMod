package com.hexvane.eterniamod.socialui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hexvane.eterniamod.domain.CollectionService;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.domain.OwnershipService;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.ui.ContentImages;
import com.hexvane.eterniamod.ui.CurrencyUi;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * The Journal's pet menu: every pet in the game as a card, the ones you have and the ones still to find, with a big
 * picture of the chosen pet beside them. Pets still to find show as a dark silhouette with a lock and say how to get
 * them. What the menu says about each pet lives in {@code Server/EterniaMod/Pets/guide.json}.
 */
final class PetMenu {
    private PetMenu() {}

    static final String GUIDE = "Server/EterniaMod/Pets/guide.json";
    private static final int PER_ROW = 4;
    private static final String GOLD = "#d8b66b", TEXT = "#f0e8d5", MUTED = "#b5c3b8", HOME = "#85c5b3";

    enum State { FOLLOWING, HOME, READY, LOCKED }

    /** One pet. {@code petId} is set once the pet has been welcomed; a pet owned but never summoned has only its grant. */
    record Card(String contentId, String name, State state, UUID petId, String rank, String colour, boolean membership,
                String moves, String description, List<String> moods, long price) {
        boolean owned() { return state != State.LOCKED; }
        boolean store() { return rank.isEmpty(); }
        String source() { return store() ? "Crown Store" : rank + " rank"; }
        String sourceColour() { return store() ? GOLD : colour; }
    }

    record Screen(List<Card> cards, boolean home) {
        Optional<Card> follower() { return cards.stream().filter(c -> c.state == State.FOLLOWING).findFirst(); }
        long owned() { return cards.stream().filter(Card::owned).count(); }
        List<Card> shown(String filter) {
            return cards.stream().filter(c -> switch (filter) { case "yours" -> c.owned(); case "find" -> !c.owned(); default -> true; }).toList();
        }
    }

    private record Guide(String rank, String colour, boolean membership, String moves, String description, List<String> moods) {
        static final Guide NONE = new Guide("", MUTED, false, "", "", List.of());
    }

    private static volatile Map<String, Guide> guide;
    private static volatile List<String> order;

    private static Map<String, Guide> guide() {
        var known = guide;
        if (known != null) return known;
        var loaded = new LinkedHashMap<String, Guide>();
        try (InputStream stream = PetMenu.class.getClassLoader().getResourceAsStream(GUIDE)) {
            if (stream != null) loaded.putAll(parse(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        } catch (Exception unreadable) { loaded.clear(); }
        order = List.copyOf(loaded.keySet());
        guide = Collections.unmodifiableMap(loaded);
        return guide;
    }

    static Map<String, Guide> parse(Reader json) {
        var out = new LinkedHashMap<String, Guide>();
        for (var element : JsonParser.parseReader(json).getAsJsonObject().getAsJsonArray("Pets")) {
            JsonObject pet = element.getAsJsonObject();
            var moods = new ArrayList<String>();
            if (pet.has("moods")) pet.getAsJsonArray("moods").forEach(m -> moods.add(m.getAsString()));
            out.put(pet.get("id").getAsString(), new Guide(text(pet, "rank"), pet.has("colour") ? text(pet, "colour") : MUTED,
                pet.has("membership") && pet.get("membership").getAsBoolean(), text(pet, "moves"), text(pet, "description"), List.copyOf(moods)));
        }
        return out;
    }

    private static String text(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsString() : ""; }

    /** Reads everything the menu shows. Runs away from the world thread. */
    static Screen load(EterniaServices services, UUID actor) {
        Owner owner = Owner.player(actor);
        var guide = guide();
        var definitions = services.collection().definitions().stream().filter(d -> d.kind() == CollectionService.Kind.PET).toList();
        var pets = services.collection().pets(owner);
        var grants = services.ownership().grants(owner);
        Map<String, Long> prices = new HashMap<>();
        for (var item : services.premium().listed())
            for (var benefit : item.benefits()) prices.merge(benefit.contentId(), item.price(), Math::min);
        var sorted = new ArrayList<>(definitions);
        sorted.sort(Comparator.comparingInt((CollectionService.Definition d) -> { int i = order.indexOf(d.id()); return i < 0 ? Integer.MAX_VALUE : i; })
            .thenComparing(CollectionService.Definition::name));
        var cards = new ArrayList<Card>();
        for (var definition : sorted) {
            var info = guide.getOrDefault(definition.id(), Guide.NONE);
            var mine = pets.stream().filter(p -> p.contentId().equals(definition.id())).toList();
            State state; UUID petId = null;
            var following = mine.stream().filter(p -> p.assignment().equals("FOLLOWER")).findFirst();
            var atHome = mine.stream().filter(p -> p.assignment().equals("PROPERTY")).findFirst();
            if (following.isPresent()) { state = State.FOLLOWING; petId = following.get().id(); }
            else if (atHome.isPresent()) { state = State.HOME; petId = atHome.get().id(); }
            else if (!mine.isEmpty()) { state = State.READY; petId = mine.getFirst().id(); }
            else state = freeSlot(grants, pets, definition.id()).isPresent() ? State.READY : State.LOCKED;
            cards.add(new Card(definition.id(), definition.name(), state, petId, info.rank(), info.colour(), info.membership(), info.moves(),
                info.description(), info.moods(), prices.getOrDefault(definition.id(), -1L)));
        }
        boolean home = services.housing().home(owner).filter(slot -> slot.state() == HousingService.State.ACTIVE).isPresent();
        return new Screen(List.copyOf(cards), home);
    }

    private record Slot(String grant, long index) {}

    /** A pet this player owns but has not welcomed yet: an active grant with a copy left over. */
    private static Optional<Slot> freeSlot(List<OwnershipService.Grant> grants, List<CollectionService.Pet> pets, String content) {
        for (var grant : grants) {
            if (!grant.contentId().equals(content) || grant.revoked() || grant.validUntil() != null && !grant.validUntil().isAfter(Instant.now())) continue;
            Set<Long> used = new HashSet<>();
            pets.stream().filter(p -> p.sourceGrant().equals(grant.id())).forEach(p -> used.add(p.sourceIndex()));
            long next = 0;
            while (used.contains(next)) next++;
            if (next < grant.quantity()) return Optional.of(new Slot(grant.id(), next));
        }
        return Optional.empty();
    }

    /** The pet to show large: the one asked for if it is still in the list, else the one following, else the first. */
    static Card chosen(Screen screen, List<Card> shown, String selected) {
        return shown.stream().filter(c -> c.contentId.equals(selected)).findFirst()
            .or(() -> shown.stream().filter(c -> c.state == State.FOLLOWING).findFirst())
            .orElse(shown.isEmpty() ? null : shown.getFirst());
    }

    /** Draws the menu into the Collection layout: filters and cards in {@code #Rows}, the chosen pet in {@code #PetPanel}. */
    static void render(UICommandBuilder c, Screen screen, String filter, String selected, BiConsumer<String, String> bind, boolean busy) {
        var shown = screen.shown(filter);
        int total = screen.cards().size(); long owned = screen.owned();
        c.append("#Rows", "EterniaMod/PetFilters.ui");
        String[][] filters = {{"all", "All", "#PetFilterAll", Integer.toString(total)}, {"yours", "Yours", "#PetFilterYours", Long.toString(owned)},
            {"find", "Still to find", "#PetFilterFind", Long.toString(total - owned)}};
        for (String[] f : filters) {
            boolean on = f[0].equals(filter);
            String label = f[1] + "  " + f[3];
            c.set("#Rows[0] " + f[2] + "On.Visible", on); c.set("#Rows[0] " + f[2] + ".Visible", !on);
            c.set("#Rows[0] " + f[2] + "On.Text", label); c.set("#Rows[0] " + f[2] + ".Text", label);
            c.set("#Rows[0] " + f[2] + ".Disabled", busy);
            if (!on) bind.accept("#Rows[0] " + f[2], "pet_filter:" + f[0]);
        }
        c.set("#Rows[0] #PetCount.Text", owned + " of " + total + " found");
        Card pick = chosen(screen, shown, selected);
        if (shown.isEmpty()) {
            c.append("#Rows", "EterniaMod/PetEmpty.ui");
            c.set("#Rows[1] #PetEmptyText.Text", filter.equals("find") ? "You have found every pet. Well done!" : "You do not have any pets yet. Supporter ranks and the Crown Store bring new friends.");
        }
        for (int i = 0; i < shown.size(); i++) {
            String row = "#Rows[" + (1 + i / PER_ROW) + "]";
            int slot = i % PER_ROW;
            if (slot == 0) {
                c.append("#Rows", "EterniaMod/PetCardRow.ui");
                for (int s = 0; s < PER_ROW; s++) c.set(row + " #PetCard" + s + ".Visible", i + s < shown.size());
            }
            Card card = shown.get(i);
            String tile = row + " #PetCard" + slot;
            c.setObject(tile + " #PetCardPicture.Background", new PatchStyle(Value.of(picture(card))));
            c.set(tile + " #PetCardLock.Visible", !card.owned());
            c.set(tile + " #PetCardBadge.Visible", card.state == State.FOLLOWING);
            c.set(tile + " #PetPick.Visible", pick != null && card.contentId.equals(pick.contentId));
            c.set(tile + " #PetCardName.Text", card.name);
            c.set(tile + " #PetCardSource.TextSpans", Message.raw(card.source()).color(card.sourceColour()));
            boolean gold = card.state == State.FOLLOWING;
            c.set(tile + " #PetCardGold.Visible", gold); c.set(tile + " #PetCardPill.Visible", !gold);
            if (gold) c.set(tile + " #PetCardGoldText.Text", "Following you");
            else c.set(tile + " #PetCardPillText.TextSpans", pill(card));
            bind.accept(tile, "pet_select:" + card.contentId);
        }
        c.set("#PetPanel.Visible", pick != null);
        if (pick != null) panel(c, screen, pick, bind, busy);
    }

    private static Message pill(Card card) {
        return switch (card.state) {
            case FOLLOWING -> Message.raw("Following you").color(TEXT);
            case HOME -> Message.raw("Living at home").color(HOME);
            case READY -> Message.raw("Ready to summon").color(TEXT);
            case LOCKED -> card.store() ? Message.raw(card.price >= 0 ? CurrencyUi.amount(card.price) + " Crowns" : "Not in the store").color(GOLD)
                : Message.raw(card.membership ? card.rank + " members" : "Get " + card.rank + " rank").color(card.colour);
        };
    }

    private static String picture(Card card) {
        String icon = ContentImages.path(card.contentId, false);
        if (card.owned() || !icon.endsWith("/icon.png")) return icon;
        return icon.substring(0, icon.length() - "icon.png".length()) + "silhouette.png";
    }

    private static void panel(UICommandBuilder c, Screen screen, Card pet, BiConsumer<String, String> bind, boolean busy) {
        c.setObject("#PetPicture.Background", new PatchStyle(Value.of(picture(pet))));
        c.set("#PetLock.Visible", !pet.owned());
        boolean badge = pet.state == State.FOLLOWING || pet.state == State.HOME;
        c.set("#PetBadge.Visible", badge);
        if (badge) c.set("#PetBadgeText.Text", pet.state == State.FOLLOWING ? "FOLLOWING YOU" : "AT HOME");
        c.set("#PetName.Text", pet.name);
        c.setObject("#PetRankPill.Background", new PatchStyle().setColor(Value.of(pet.sourceColour())));
        c.set("#PetRank.Text", pet.source());
        c.set("#PetMove.Text", pet.moves.isEmpty() ? "Pet" : pet.moves);
        c.set("#PetDescription.Text", pet.description.isEmpty() ? "A loyal little companion." : pet.description);
        c.set("#PetMoods.Visible", pet.owned() && !pet.moods.isEmpty());
        for (int i = 0; i < 4; i++) {
            boolean has = i < pet.moods.size();
            c.set("#PetMoodChip" + i + ".Visible", has);
            if (has) c.set("#PetMood" + i + ".Text", pet.moods.get(i));
        }
        c.set("#PetUnlock.Visible", !pet.owned());
        if (!pet.owned()) c.set("#PetUnlockText.Text", unlock(pet));
        String primary = "", primaryRoute = "", secondary = "", secondaryRoute = "", note = "";
        String content = pet.contentId;
        var follower = screen.follower().filter(f -> !f.contentId.equals(content));
        switch (pet.state) {
            case FOLLOWING -> {
                primary = "Dismiss"; primaryRoute = "petmenu_dismiss:" + content;
                secondary = "Let it live at home"; secondaryRoute = screen.home() ? "petmenu_home:" + content : "";
                note = screen.home() ? "It is with you now. Dismiss it, or let it play at your home." : "Claim a plot to let your pets live at home.";
            }
            case HOME -> {
                primary = "Summon"; primaryRoute = "petmenu_summon:" + content;
                secondary = "Stop living at home"; secondaryRoute = "petmenu_dismiss:" + content;
                note = follower.map(f -> f.name + " will stop following you.").orElse("It is playing at your home.");
            }
            case READY -> {
                primary = "Summon"; primaryRoute = "petmenu_summon:" + content;
                secondary = "Let it live at home"; secondaryRoute = screen.home() ? "petmenu_home:" + content : "";
                note = follower.map(f -> f.name + " will stop following you.").orElse(screen.home() ? "One pet can follow you at a time." : "Claim a plot to let your pets live at home.");
            }
            case LOCKED -> {
                if (pet.store() && pet.price >= 0) { primary = "Open Crown Store"; primaryRoute = "nav:STORE"; }
                else { primary = "Locked"; primaryRoute = ""; }
            }
        }
        c.set("#PetNote.Text", note);
        c.set("#PetPrimary.Text", primary);
        c.set("#PetPrimary.Disabled", busy || primaryRoute.isEmpty());
        if (!primaryRoute.isEmpty()) bind.accept("#PetPrimary", primaryRoute);
        c.set("#PetSecondary.Visible", !secondary.isEmpty());
        c.set("#PetSecondary.Text", secondary);
        c.set("#PetSecondary.Disabled", busy || secondaryRoute.isEmpty());
        if (!secondaryRoute.isEmpty()) bind.accept("#PetSecondary", secondaryRoute);
    }

    private static String unlock(Card pet) {
        if (pet.store()) return pet.price >= 0 ? "Find it in the Crown Store for " + CurrencyUi.amount(pet.price) + " Crowns." : "This pet is not in the Crown Store right now.";
        if (pet.membership) return "Comes with the " + pet.rank + " membership. It stays with you for as long as your membership is active.";
        return "Become a " + pet.rank + " supporter to welcome this pet. You keep it for good.";
    }

    /** Summon, send home or dismiss a pet. Runs away from the world thread; returns the message to show. */
    static String act(EterniaServices services, UUID actor, String route) {
        Owner owner = Owner.player(actor);
        String action = route.substring("petmenu_".length(), route.indexOf(':'));
        String content = route.substring(route.indexOf(':') + 1);
        var collection = services.collection();
        var pets = collection.pets(owner);
        var mine = pets.stream().filter(p -> p.contentId().equals(content)).toList();
        if (action.equals("dismiss")) {
            var placed = mine.stream().filter(p -> !p.assignment().equals("UNASSIGNED")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That pet is already resting."));
            boolean following = placed.assignment().equals("FOLLOWER");
            collection.unassign(actor, placed.id());
            return following ? "Your pet is taking a rest." : "Your pet has left your home.";
        }
        var pet = mine.stream().filter(p -> p.assignment().equals(action.equals("summon") ? "FOLLOWER" : "PROPERTY")).findFirst()
            .or(() -> mine.stream().findFirst())
            .orElseGet(() -> {
                var slot = freeSlot(services.ownership().grants(owner), pets, content)
                    .orElseThrow(() -> new IllegalArgumentException("You do not have this pet yet."));
                return collection.materializePet(owner, slot.grant(), slot.index());
            });
        if (action.equals("home")) {
            var property = services.housing().home(owner).filter(s -> s.state() == HousingService.State.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Claim a plot first so your pet has a home."));
            collection.assignProperty(actor, pet.id(), property.propertyId());
            return "Your pet is settling in at home.";
        }
        if (!action.equals("summon")) throw new IllegalArgumentException("This action is no longer available. Refresh this section.");
        collection.follow(actor, pet.id());
        return "Your pet will join you shortly.";
    }
}
