package dev.berth9.engine.format;

import java.util.Locale;

/** File formats partners send. */
public enum SourceFormat {
    CSV,
    XLSX,
    X12,
    XML,
    FIXED_WIDTH,
    JSON,
    UNKNOWN;

    public static SourceFormat parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String v = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (v) {
            case "EDI", "X12" -> X12;
            case "FIXED", "FIXEDWIDTH", "FIXED_WIDTH", "FLAT" -> FIXED_WIDTH;
            case "EXCEL", "XLSX" -> XLSX;
            default -> valueOf(v);
        };
    }
}
