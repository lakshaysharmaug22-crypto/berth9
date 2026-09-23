package dev.berth9.engine.map;

import dev.berth9.engine.model.Violation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A record in canonical shape.
 *
 * @param line    source line it came from
 * @param values  target field to typed value (BigDecimal, Long, LocalDate, String, Boolean)
 * @param lineage target field to where it came from ("Inv Dt", "const", "computed", "catalog")
 * @param issues  problems found while mapping (unparseable dates, bad amounts)
 */
public record MappedRecord(long line, Map<String, Object> values, Map<String, List<String>> lineage, List<Violation> issues) {

    public MappedRecord {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        lineage = Map.copyOf(lineage);
        issues = List.copyOf(issues);
    }

    public Object get(String field) {
        return values.get(field);
    }
}
