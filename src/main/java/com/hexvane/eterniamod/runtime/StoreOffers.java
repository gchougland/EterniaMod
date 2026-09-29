package com.hexvane.eterniamod.runtime;

import com.google.gson.*;
import com.hexvane.eterniamod.domain.EterniaServices;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Presentation points to configured Tebex checkout pages; prices and grants remain provider-controlled. */
public final class StoreOffers {
    public record Offer(String id, int revision, String name, String description, String checkoutUrl) {}
    private StoreOffers() {}
    public static List<Offer> load(Path file, EterniaServices services) {
        try {
            if (!Files.exists(file)) { Files.createDirectories(file.getParent()); Files.writeString(file, "[]\n", StandardOpenOption.CREATE_NEW); }
            if (Files.isSymbolicLink(file) || Files.size(file) > 1024 * 1024) throw new IOException("Invalid store offers file");
            var offers = new Gson().fromJson(Files.readString(file), Offer[].class);
            if (offers == null || offers.length > 200) throw new IllegalArgumentException("Invalid store offers");
            var seen = new HashSet<String>(); var products = services.commerce().products();
            for (Offer offer : offers) {
                if (offer == null || offer.id() == null || !seen.add(offer.id()) || offer.name() == null || offer.name().isBlank() || offer.description() == null)
                    throw new IllegalArgumentException("Invalid or duplicate store offer");
                if (products.stream().noneMatch(p -> p.packageId().equals(offer.id()) && p.revision() == offer.revision()))
                    throw new IllegalArgumentException("Store offer must reference a registered product revision: " + offer.id());
                URI url = URI.create(offer.checkoutUrl()); String host = url.getHost();
                if (!"https".equals(url.getScheme()) || url.getUserInfo() != null || host == null ||
                    !(host.equals("tebex.io") || host.endsWith(".tebex.io"))) throw new IllegalArgumentException("Store checkout must use an HTTPS Tebex URL");
            }
            return List.of(offers);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
