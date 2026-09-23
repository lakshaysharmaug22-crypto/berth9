package dev.berth9.engine.transform;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Lookup tables available to transforms (unit-of-measure codes, currency aliases, partner code lists). */
public final class TransformContext {

    private final Map<String, Map<String, String>> tables;

    public TransformContext(Map<String, Map<String, String>> tables) {
        Map<String, Map<String, String>> normalized = new HashMap<>();
        if (tables != null) {
            tables.forEach((name, rows) -> {
                Map<String, String> byKey = new HashMap<>();
                rows.forEach((k, v) -> byKey.put(k.strip().toUpperCase(Locale.ROOT), v));
                normalized.put(name, Map.copyOf(byKey));
            });
        }
        this.tables = Map.copyOf(normalized);
    }

    public static TransformContext empty() {
        return new TransformContext(Map.of());
    }

    /** Case-insensitive lookup; returns null when the table or key is unknown. */
    public String lookup(String table, String key) {
        Map<String, String> rows = tables.get(table);
        if (rows == null || key == null) {
            return null;
        }
        return rows.get(key.strip().toUpperCase(Locale.ROOT));
    }

    public boolean hasTable(String table) {
        return tables.containsKey(table);
    }
}
