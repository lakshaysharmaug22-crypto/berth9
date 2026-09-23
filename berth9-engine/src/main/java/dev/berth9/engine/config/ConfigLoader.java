package dev.berth9.engine.config;

import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.map.Enrichment;
import dev.berth9.engine.map.FieldMapping;
import dev.berth9.engine.map.MappingSpec;
import dev.berth9.engine.model.FieldType;
import dev.berth9.engine.model.Severity;
import dev.berth9.engine.model.TargetField;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.model.ValueKind;
import dev.berth9.engine.read.FixedColumn;
import dev.berth9.engine.read.ReaderOptions;
import dev.berth9.engine.rules.RefCheck;
import dev.berth9.engine.rules.Rule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds engine objects from parsed JSON ({@link Json} or any library that yields maps and lists).
 * Errors name the offending element so a bad spec is easy to fix from the console.
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    // ---------------------------------------------------------------- schema

    public static TargetSchema schema(Map<String, Object> json) {
        List<TargetField> fields = new ArrayList<>();
        for (Map<String, Object> f : objects(json.get("fields"), "fields")) {
            Set<ValueKind> kinds = EnumSet.noneOf(ValueKind.class);
            strings(f.get("kinds")).forEach(k -> kinds.add(ValueKind.valueOf(k.toUpperCase(Locale.ROOT))));
            fields.add(new TargetField(str(f, "name"),
                    FieldType.valueOf(str(f, "type", "STRING").toUpperCase(Locale.ROOT)),
                    bool(f, "required", false),
                    str(f, "label", null),
                    strings(f.get("aliases")),
                    kinds,
                    bool(f, "derived", false)));
        }
        return new TargetSchema(str(json, "name"), str(json, "label", null), fields, strings(json.get("businessKey")));
    }

    // ---------------------------------------------------------------- mapping spec

    public static MappingSpec spec(Map<String, Object> json) {
        List<FieldMapping> fields = new ArrayList<>();
        for (Map<String, Object> f : objects(json.get("fields"), "fields")) {
            List<String> sources = f.containsKey("sources") ? strings(f.get("sources"))
                    : f.get("source") != null ? List.of(str(f, "source")) : List.of();
            fields.add(new FieldMapping(str(f, "target"), sources, str(f, "join", null), str(f, "const", null), str(f, "transform", null)));
        }
        List<Enrichment> enrichments = new ArrayList<>();
        for (Map<String, Object> e : objects(json.get("enrich"), "enrich")) {
            enrichments.add(enrichment(e));
        }
        return new MappingSpec(str(json, "partnerId"), integer(json, "version", 1), str(json, "schema"),
                SourceFormat.parse(str(json, "format", "UNKNOWN")), reader(asMap(json.get("reader"))), fields, enrichments);
    }

    static ReaderOptions reader(Map<String, Object> r) {
        if (r == null) {
            return ReaderOptions.defaults();
        }
        String delimiter = str(r, "delimiter", null);
        Character delim = delimiter == null || delimiter.isEmpty() ? null : ("\\t".equals(delimiter) || "tab".equalsIgnoreCase(delimiter) ? '\t' : delimiter.charAt(0));
        Map<String, List<FixedColumn>> layouts = new LinkedHashMap<>();
        Map<String, Object> fixed = asMap(r.get("fixed"));
        String detail = null;
        String trailer = null;
        if (fixed != null) {
            Map<String, Object> rawLayouts = asMap(fixed.get("layouts"));
            if (rawLayouts != null) {
                rawLayouts.forEach((type, cols) -> {
                    List<FixedColumn> columns = new ArrayList<>();
                    for (Map<String, Object> c : objects(cols, "layout " + type)) {
                        columns.add(new FixedColumn(str(c, "name"), integer(c, "start", 1), integer(c, "length", 1)));
                    }
                    layouts.put(type, columns);
                });
            }
            detail = str(fixed, "detail", null);
            trailer = str(fixed, "trailer", null);
        }
        Integer headerRow = r.get("headerRow") == null ? null : integer(r, "headerRow", 1);
        return new ReaderOptions(delim, headerRow, str(r, "sheet", null), str(r, "recordElement", null),
                str(r, "recordSegment", null), layouts, detail, trailer, str(r, "charset", null));
    }

    static Enrichment enrichment(Map<String, Object> e) {
        String type = str(e, "type").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "lookup" -> {
                Map<String, String> copy = new LinkedHashMap<>();
                Map<String, Object> raw = asMap(e.get("copy"));
                if (raw != null) {
                    raw.forEach((k, v) -> copy.put(k, String.valueOf(v)));
                }
                yield new Enrichment.Lookup(str(e, "dataset"), str(e, "key"), copy, bool(e, "onlyIfBlank", true));
            }
            case "convert" -> new Enrichment.Convert(str(e, "amount"), str(e, "currency"), str(e, "target"), str(e, "base", "USD"));
            case "compute" -> new Enrichment.Compute(str(e, "target"), str(e, "left"), str(e, "op"), str(e, "right"), integer(e, "scale", 2));
            default -> throw new IllegalArgumentException("unknown enrichment type '" + type + "'");
        };
    }

    // ---------------------------------------------------------------- rules

    public static List<Rule> rules(Map<String, Object> json) {
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> r : objects(json.get("rules"), "rules")) {
            rules.add(rule(r));
        }
        return rules;
    }

    static Rule rule(Map<String, Object> r) {
        String type = str(r, "type").toLowerCase(Locale.ROOT);
        String id = str(r, "id");
        Severity severity = Severity.valueOf(str(r, "severity", "ERROR").toUpperCase(Locale.ROOT));
        return switch (type) {
            case "required" -> new Rule.Required(id, str(r, "field"), severity);
            case "matches" -> new Rule.Matches(id, str(r, "field"), str(r, "regex"), str(r, "message", null), severity);
            case "range" -> new Rule.Range(id, str(r, "field"), decimal(r.get("min")), decimal(r.get("max")), severity);
            case "datewindow" -> new Rule.DateWindow(id, str(r, "field"),
                    r.get("maxPastDays") == null ? null : integer(r, "maxPastDays", 0),
                    r.get("maxFutureDays") == null ? null : integer(r, "maxFutureDays", 0), severity);
            case "oneof" -> new Rule.OneOf(id, str(r, "field"), strings(r.get("allowed")), severity);
            case "unique" -> new Rule.UniqueInFile(id, strings(r.get("fields")), severity);
            case "arithmetic" -> new Rule.Arithmetic(id, str(r, "field"), str(r, "left"), str(r, "op"), str(r, "right"),
                    decimal(r.get("tolerance")), decimal(r.get("tolerancePct")), severity);
            case "gstin" -> new Rule.Gstin(id, str(r, "field"), severity);
            case "reference" -> {
                List<RefCheck> checks = new ArrayList<>();
                for (Map<String, Object> c : objects(r.get("checks"), "checks")) {
                    checks.add(check(c));
                }
                yield new Rule.Reference(id, str(r, "dataset"), str(r, "key"), bool(r, "mustExist", true),
                        str(r, "message", null), checks, severity);
            }
            case "when" -> {
                List<Rule> nested = new ArrayList<>();
                for (Map<String, Object> n : objects(r.get("then"), "then")) {
                    nested.add(rule(n));
                }
                yield new Rule.When(id, str(r, "field"), strings(r.get("in")), nested, severity);
            }
            default -> throw new IllegalArgumentException("unknown rule type '" + type + "' in rule " + id);
        };
    }

    static RefCheck check(Map<String, Object> c) {
        String type = str(c, "type").toLowerCase(Locale.ROOT);
        Severity severity = Severity.valueOf(str(c, "severity", "ERROR").toUpperCase(Locale.ROOT));
        return switch (type) {
            case "equals" -> new RefCheck.Equals(str(c, "field"), str(c, "refField"), str(c, "label", null), severity);
            case "withinpct" -> new RefCheck.WithinPct(str(c, "field"), str(c, "refField"), str(c, "label", null),
                    decimal(c.get("warnPct")), decimal(c.get("errorPct")));
            case "atmost" -> new RefCheck.AtMost(str(c, "field"), str(c, "refField"), str(c, "minus", null), str(c, "label", null),
                    decimal(c.get("tolerancePct")), severity);
            default -> throw new IllegalArgumentException("unknown reference check '" + type + "'");
        };
    }

    // ---------------------------------------------------------------- lookups

    public static Map<String, Map<String, String>> lookups(Map<String, Object> json) {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        json.forEach((table, rows) -> {
            Map<String, String> entries = new LinkedHashMap<>();
            Map<String, Object> raw = asMap(rows);
            if (raw != null) {
                raw.forEach((k, v) -> entries.put(k, String.valueOf(v)));
            }
            tables.put(table, entries);
        });
        return tables;
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    static List<Map<String, Object>> objects(Object o, String what) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o == null) {
            return out;
        }
        if (!(o instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + what + "' must be a list");
        }
        for (Object item : list) {
            Map<String, Object> m = asMap(item);
            if (m == null) {
                throw new IllegalArgumentException("'" + what + "' must contain objects");
            }
            out.add(m);
        }
        return out;
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            list.forEach(item -> out.add(String.valueOf(item)));
        } else if (o != null) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return v instanceof BigDecimal b ? b.toPlainString() : v.toString();
    }

    static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : v instanceof BigDecimal b ? b.toPlainString() : v.toString();
    }

    static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        return v == null ? fallback : v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
    }

    static int integer(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        if (v == null) {
            return fallback;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString().strip());
    }

    static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString().strip());
    }
}
