package com.hexvane.eterniamod.gatehouse;

import static com.hexvane.eterniamod.gatehouse.GatehouseFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The desk home's commands: what is shown and bound in each state, and that every id it addresses exists. The engine's
 * command builder needs Hytale's log manager, which the shared test JVM cannot take, so the checks run in a child JVM
 * the way the other native tests do. It also writes the commands for ui_render to build/gatehouse-ui/.
 */
class GatehouseDeskTest {
    @Test void deskCommandsInEveryState(@TempDir Path temp) throws Exception {
        String cp = String.join(java.io.File.pathSeparator,
            Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(GatehouseDesk.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(UICommandBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path log = temp.resolve("desk.log"), failure = temp.resolve("failure.txt");
        String java = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", java).toString(),
            "-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager",
            "-cp", cp, Probe.class.getName(), failure.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Gatehouse desk test timed out");
            assertEquals(0, process.exitValue(), Files.exists(failure) ? Files.readString(failure) : Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        assertTrue(Files.exists(Path.of("build/gatehouse-ui/several-active.json")));
    }

    public static final class Probe {
        private static final Path UI = Path.of("src/main/resources/Common/UI/Custom/EterniaMod");

        public static void main(String[] args) {
            try {
                write("empty", deskHome(empty(), "", false));
                write("one-not-active", deskHome(oneNotActive(), "", false));
                write("several-active", deskHome(severalActive(), "Tidewater is now your active pass. Your other passes keep their progress.", false));
                write("overfull", deskHome(overfull(), "", false));
                write("several-busy", deskHome(severalActive(), "", true));
                write("premium-upcoming", deskHome(premiumUpcoming(), "", false));
                routesInEachState();
                emptyDeskShowsTheClosedGate();
                everyAddressedIdExists();
            } catch (Throwable failure) {
                var text = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(text));
                try { Files.writeString(Path.of(args[0]), text.toString()); } catch (Exception ignored) {}
                System.exit(1);
            }
        }

        private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

        private static List<String> routes(GatehouseBoard board, boolean busy) {
            var routes = new ArrayList<String>();
            GatehouseDesk.render(new UICommandBuilder(), board, (selector, route) -> routes.add(selector + " -> " + route), busy, "");
            return routes;
        }

        private static void routesInEachState() {
            var several = routes(severalActive(), false);
            check(several.contains("#GatePasses[0] #PassSlot[0] -> gate_view:eternia:foundations"), "banner opens its pass " + several);
            check(several.contains("#GatePasses[0] #PassClaim -> gate_claim:eternia:foundations"), "claim on a pass with a ready reward");
            check(several.contains("#GatePasses[0] #PassActivate -> gate_activate:eternia:foundations"), "switch to a paused pass");
            check(several.contains("#GatePasses[1] #PassClaim -> gate_claim:eternia:tidewater"), "claim on the active pass");
            check(several.stream().noneMatch(r -> r.endsWith("gate_activate:eternia:tidewater")), "the active pass has no switch button");
            check(several.stream().noneMatch(r -> r.endsWith("gate_claim:eternia:emberfall")), "nothing to claim on Emberfall");
            check(several.contains("#BountyAll -> gate_quests"), "quest log link");
            check(several.stream().noneMatch(r -> r.endsWith("external:STORE_OPEN")), "premium already owned on the active pass");
            var one = routes(oneNotActive(), false);
            check(one.contains("#PremiumBuy -> external:STORE_OPEN"), "premium link when not owned");
            check(one.contains("#GatePasses[0] #PassActivate -> gate_activate:eternia:foundations"), "activate the only pass");
            check(!one.contains("#BountyAll -> gate_quests"), "no quest log without an active pass");
            check(routes(severalActive(), true).isEmpty(), "a busy desk binds nothing");
            check(routes(premiumUpcoming(), false).stream().noneMatch(r -> r.endsWith("external:STORE_OPEN")), "no store link while premium is not on sale");
        }

        private static void emptyDeskShowsTheClosedGate() {
            var set = new LinkedHashMap<String, String>();
            for (var command : deskHome(empty(), "", false).getCommands()) if (command.type.name().equals("Set")) set.put(command.selector, com.google.gson.JsonParser.parseString(command.data).toString().replaceAll("^\\{\"0\":(.*)\\}$", "$1"));
            check("false".equals(set.get("#GatePasses.Visible")), "no banner row " + set.get("#GatePasses.Visible"));
            check("true".equals(set.get("#GateEmpty.Visible")), "closed gate card");
            check("false".equals(set.get("#PremiumBuy.Visible")), "no premium link");
            check("false".equals(set.get("#BountyAll.Visible")), "no quest log link");
        }

        private static void everyAddressedIdExists() throws Exception {
            var ids = new HashSet<String>();
            for (String doc : List.of("ServicesPage.ui", "Wallet.ui", "GatehouseDesk.ui", "GatehousePass.ui", "SeasonBanner.ui", "GatehouseBounty.ui", "GatehouseBountyHead.ui", "GatehouseBountyNote.ui")) {
                var m = Pattern.compile("#([A-Za-z0-9]+)").matcher(Files.readString(UI.resolve(doc)));
                while (m.find()) ids.add(m.group(1));
            }
            for (var board : List.of(empty(), oneNotActive(), severalActive(), overfull()))
                for (var command : deskHome(board, "", false).getCommands()) {
                    if (command.selector == null) continue;
                    var m = Pattern.compile("#([A-Za-z0-9]+)").matcher(command.selector);
                    while (m.find()) check(ids.contains(m.group(1)), "missing #" + m.group(1) + " in " + command.selector);
                }
        }
    }
}
