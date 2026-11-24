package com.wms.builder;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which part of the WMS a piece of the code belongs to, in the words people use for it. Steps are shown by this to people
 * who don't see the code: "Looked at locations", never which file.
 */
final class WmsAreas {

    /**
     * Most particular first: the first whose word begins one of the words in the path names it. Words are folder names and
     * the parts of file names, so "OrderAllocation" is about orders, not locations.
     */
    private static final List<Map.Entry<String, String>> AREAS = List.of(
            Map.entry("rf", "handheld screens"),
            Map.entry("pack", "packing"),
            Map.entry("ship", "shipping"),
            Map.entry("manifest", "shipping"),
            Map.entry("pick", "picking"),
            Map.entry("location", "locations"),
            Map.entry("zone", "zones"),
            Map.entry("container", "containers and LPNs"),
            Map.entry("lpn", "containers and LPNs"),
            Map.entry("owner", "owners"),
            Map.entry("carrier", "carriers"),
            Map.entry("uom", "units of measure"),
            Map.entry("item", "items"),
            Map.entry("facilit", "facilities"),
            Map.entry("putaway", "put-away"),
            Map.entry("replenish", "replenishment"),
            Map.entry("asn", "receiving"),
            Map.entry("receiv", "receiving"),
            Map.entry("inbound", "receiving"),
            Map.entry("count", "cycle counts"),
            Map.entry("adjust", "stock adjustments"),
            Map.entry("hold", "holds"),
            Map.entry("move", "stock moves"),
            Map.entry("ledger", "stock history"),
            Map.entry("history", "stock history"),
            Map.entry("control", "inventory control"),
            Map.entry("inventory", "inventory"),
            Map.entry("allocat", "orders"),
            Map.entry("wave", "orders"),
            Map.entry("order", "orders"),
            Map.entry("outbound", "orders"),
            Map.entry("task", "the task queue"),
            Map.entry("work", "the task queue"),
            Map.entry("role", "users and permissions"),
            Map.entry("user", "users and permissions"),
            Map.entry("session", "signing in"),
            Map.entry("catalog", "menus"),
            Map.entry("navigation", "menus"),
            Map.entry("migration", "how information is stored"));

    /** Words that only count whole: short ones that would begin too many others. */
    private static final List<String> WHOLE = List.of("rf", "asn", "lpn", "uom", "work");

    private WmsAreas() {
    }

    /** The part of the WMS {@code path} is in, or null for none in particular. */
    static String of(Object path) {
        if (!(path instanceof String text) || text.isBlank()) {
            return null;
        }
        String clean = text.replace('\\', '/');
        // Folder names, and file names split into their words: OrderAllocation.java is "order", "allocation", "java".
        List<String> words = Arrays.stream(clean.split("[/._\\-]+|(?<=[a-z0-9])(?=[A-Z])"))
                .map(word -> word.toLowerCase(Locale.ROOT)).filter(word -> !word.isEmpty()).toList();
        for (Map.Entry<String, String> area : AREAS) {
            String key = area.getKey();
            if (words.stream().anyMatch(word -> WHOLE.contains(key) ? word.equals(key) : word.startsWith(key))) {
                return area.getValue();
            }
        }
        return clean.startsWith("frontend/") ? "screens" : clean.startsWith("backend/") ? "the server" : null;
    }
}
