package com.hexvane.eterniamod.portals;

import java.util.Locale;

/** Player facing wording for the portal menu. */
final class PortalText {
    private PortalText() {}

    static String blocks(double d) { long n = Math.round(d); return n < 1000 ? n + " blocks away" : String.format(Locale.ROOT, "%.1fk blocks away", n / 1000.0); }
}
