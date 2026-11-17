package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Durable production records: one row per station (keyed by the placed object's id, which survives packing and
 * placing again) and one row per plot. The runtime keeps everything in memory and writes changed rows here.
 */
public final class ProductionStore {
    static final String STATIONS = "production_station", PLOTS = "production_plot";
    private final TransactionalStore store;

    public ProductionStore(TransactionalStore store) { this.store = store; }

    /** A station row: who and where it is, and its progress. {@code revision} is 0 until it has been saved once. */
    public static final class StationRow {
        public UUID id;
        public String owner = "";
        public UUID plot;
        public String world = "";
        public boolean placed;
        public int tierShown = 1;
        public final StationProgress progress = new StationProgress();
        long revision;
    }

    public static final class PlotRow {
        public UUID id;
        public final PlotProduction plot = new PlotProduction();
        long revision;
    }

    public List<StationRow> stations() {
        return store.transaction(tx -> {
            var out = new ArrayList<StationRow>();
            for (var row : tx.scan(STATIONS)) {
                try { out.add(station(row)); } catch (RuntimeException broken) { /* one unreadable row must not stop the rest */ }
            }
            return out;
        });
    }

    public List<PlotRow> plots() {
        return store.transaction(tx -> {
            var out = new ArrayList<PlotRow>();
            for (var row : tx.scan(PLOTS)) {
                try { out.add(plot(row)); } catch (RuntimeException broken) { /* skip */ }
            }
            return out;
        });
    }

    public void save(StationRow s) {
        var fields = fields(s);
        s.revision = store.transaction(tx -> tx.save(STATIONS, s.id.toString(), current(tx, STATIONS, s.id.toString(), s.revision), fields)).revision();
    }

    public void save(PlotRow p) {
        var fields = fields(p);
        p.revision = store.transaction(tx -> tx.save(PLOTS, p.id.toString(), current(tx, PLOTS, p.id.toString(), p.revision), fields)).revision();
    }

    /** The runtime is the only writer, so a stale revision means an earlier save's answer was lost: take the stored one. */
    private static long current(TransactionalStore.Transaction tx, String namespace, String key, long believed) {
        return tx.find(namespace, key).map(TransactionalStore.Row::revision).orElse(0L);
    }

    // ---- rows to objects and back ----

    static Map<String, String> fields(StationRow s) {
        var p = s.progress;
        var f = new TreeMap<String, String>();
        f.put("owner", s.owner); f.put("plot", s.plot == null ? "" : s.plot.toString()); f.put("world", s.world);
        f.put("placed", Boolean.toString(s.placed)); f.put("tierShown", Integer.toString(s.tierShown));
        f.put("profession", p.profession); f.put("level", Integer.toString(p.level)); f.put("peak", Integer.toString(p.peakLevel));
        f.put("renown", Integer.toString(p.renown)); f.put("purse", Double.toString(p.purse)); f.put("stockpile", Double.toString(p.stockpile));
        f.put("lifetime", Double.toString(p.lifetime)); f.put("best", Double.toString(p.bestHaul));
        f.put("tools", p.tools[0] + "," + p.tools[1] + "," + p.tools[2] + "," + p.tools[3]);
        f.put("talents", String.join(",", p.talents)); f.put("reshuffles", Integer.toString(p.reshuffles));
        f.put("finds", String.join(",", p.finds)); f.put("waiting", String.join(",", p.waitingFinds));
        f.put("cycles", Long.toString(p.cycles)); f.put("vein", Integer.toString(p.veinLeft));
        return f;
    }

    static StationRow station(TransactionalStore.Row row) {
        var s = new StationRow();
        var p = s.progress;
        s.id = UUID.fromString(row.key());
        s.revision = row.revision();
        s.owner = row.value("owner");
        s.plot = row.value("plot").isEmpty() ? null : UUID.fromString(row.value("plot"));
        s.world = row.value("world");
        s.placed = Boolean.parseBoolean(row.value("placed"));
        s.tierShown = (int) number(row, "tierShown", 1);
        p.profession = row.value("profession");
        p.level = (int) number(row, "level", 1); p.peakLevel = (int) number(row, "peak", p.level); p.renown = (int) number(row, "renown", 0);
        p.purse = real(row, "purse"); p.stockpile = real(row, "stockpile"); p.lifetime = real(row, "lifetime"); p.bestHaul = real(row, "best");
        String[] tools = row.value("tools").split(",");
        for (int i = 0; i < 4 && i < tools.length; i++) if (!tools[i].isEmpty()) p.tools[i] = Integer.parseInt(tools[i]);
        p.talents.addAll(list(row.value("talents"))); p.reshuffles = (int) number(row, "reshuffles", 0);
        p.finds.addAll(list(row.value("finds"))); p.waitingFinds.addAll(list(row.value("waiting")));
        p.cycles = number(row, "cycles", 0); p.veinLeft = (int) number(row, "vein", 0);
        return s;
    }

    static Map<String, String> fields(PlotRow r) {
        var p = r.plot;
        var f = new TreeMap<String, String>();
        f.put("seals", Integer.toString(p.seals)); f.put("estate", p.estate[0] + "," + p.estate[1] + "," + p.estate[2]);
        f.put("streak", Integer.toString(p.streak)); f.put("collectDay", Long.toString(p.lastCollectDay));
        f.put("energyDay", Long.toString(p.energyDay)); f.put("energyUsed", Integer.toString(p.energyUsed));
        f.put("ordersDay", Long.toString(p.ordersDay)); f.put("golden", Integer.toString(p.goldenCaught));
        f.put("roles", p.roles);
        var orders = new StringBuilder();
        for (var o : p.orders) orders.append(o.kind).append(':').append(o.goal).append(':').append(o.done).append(':').append(o.seals).append(':').append(o.paid).append(';');
        f.put("orders", orders.toString());
        return f;
    }

    static PlotRow plot(TransactionalStore.Row row) {
        var r = new PlotRow();
        var p = r.plot;
        r.id = UUID.fromString(row.key());
        r.revision = row.revision();
        p.seals = (int) number(row, "seals", 0);
        String[] estate = row.value("estate").split(",");
        for (int i = 0; i < 3 && i < estate.length; i++) if (!estate[i].isEmpty()) p.estate[i] = Integer.parseInt(estate[i]);
        p.streak = (int) number(row, "streak", 0); p.lastCollectDay = number(row, "collectDay", 0);
        p.energyDay = number(row, "energyDay", 0); p.energyUsed = (int) number(row, "energyUsed", 0);
        p.ordersDay = number(row, "ordersDay", 0); p.goldenCaught = (int) number(row, "golden", 0);
        p.roles = row.value("roles");
        for (String part : row.value("orders").split(";")) {
            String[] o = part.split(":");
            if (o.length != 5) continue;
            var order = new PlotProduction.Order(o[0], Double.parseDouble(o[1]), Integer.parseInt(o[3]));
            order.done = Double.parseDouble(o[2]); order.paid = Boolean.parseBoolean(o[4]);
            p.orders.add(order);
        }
        return r;
    }

    private static long number(TransactionalStore.Row row, String name, long fallback) {
        String v = row.value(name);
        return v.isEmpty() ? fallback : Long.parseLong(v);
    }

    private static double real(TransactionalStore.Row row, String name) {
        String v = row.value(name);
        return v.isEmpty() ? 0 : Double.parseDouble(v);
    }

    private static List<String> list(String csv) {
        var out = new ArrayList<String>();
        for (String part : csv.split(",")) if (!part.isEmpty()) out.add(part);
        return out;
    }
}
