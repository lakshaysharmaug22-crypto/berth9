package dev.berth9.engine.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One raw record exactly as a partner sent it, before any mapping.
 * Column order is preserved so the exceptions view can show the row as received.
 *
 * @param line   1-based physical position in the source file (row, line or segment number)
 * @param fields column name to raw text value
 */
public record SourceRecord(long line, Map<String, String> fields) {

    public SourceRecord {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public String get(String column) {
        return fields.get(column);
    }
}
