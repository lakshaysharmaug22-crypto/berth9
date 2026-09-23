package dev.berth9.engine.map;

import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.model.TargetField;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.model.Violation;
import dev.berth9.engine.text.Texts;
import dev.berth9.engine.transform.Amounts;
import dev.berth9.engine.transform.Coercion;
import dev.berth9.engine.transform.TransformContext;
import dev.berth9.engine.transform.TransformException;
import dev.berth9.engine.transform.TransformPipeline;
import dev.berth9.engine.transform.TransformRegistry;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies a {@link MappingSpec} to partner records. Transform expressions are compiled once when the
 * mapper is built, so a spec with a typo fails when it is saved, not halfway through a file.
 * Column names are matched case- and whitespace-insensitively ("Inv  No." finds "inv no").
 */
public final class RecordMapper {

    private record CompiledField(FieldMapping mapping, TargetField target, TransformPipeline pipeline) {
    }

    private final MappingSpec spec;
    private final List<CompiledField> fields;
    private final TransformContext context;
    private final Map<String, String> resolvedColumns = new ConcurrentHashMap<>();

    public RecordMapper(MappingSpec spec, TargetSchema schema, TransformRegistry registry, TransformContext context) {
        this.spec = spec;
        this.context = context == null ? TransformContext.empty() : context;
        List<CompiledField> compiled = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (FieldMapping m : spec.fields()) {
            TargetField target = schema.field(m.target()).orElse(null);
            if (target == null) {
                unknown.add(m.target());
                continue;
            }
            compiled.add(new CompiledField(m, target, TransformPipeline.compile(m.transform(), registry)));
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("mapping for " + spec.partnerId() + " references unknown " + schema.name()
                    + " fields: " + String.join(", ", unknown));
        }
        this.fields = List.copyOf(compiled);
    }

    public MappingSpec spec() {
        return spec;
    }

    public MappedRecord map(SourceRecord source) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, List<String>> lineage = new LinkedHashMap<>();
        List<Violation> issues = new ArrayList<>();
        for (CompiledField f : fields) {
            FieldMapping m = f.mapping();
            String name = f.target().name();
            Object raw;
            if (m.constant() != null) {
                raw = m.constant();
                lineage.put(name, List.of("const"));
            } else {
                List<String> parts = new ArrayList<>();
                List<String> usedColumns = new ArrayList<>();
                for (String column : m.sources()) {
                    String actual = resolve(column, source);
                    if (actual == null) {
                        continue;
                    }
                    usedColumns.add(actual);
                    String value = Texts.clean(source.get(actual));
                    if (value != null) {
                        parts.add(value);
                    }
                }
                lineage.put(name, usedColumns.isEmpty() ? m.sources() : usedColumns);
                try {
                    raw = combine(parts, m.join());
                } catch (TransformException e) {
                    values.put(name, null);
                    issues.add(Violation.error("MAPPING", name, f.target().label() + ": " + e.getMessage()));
                    continue;
                }
            }
            try {
                Object transformed = f.pipeline().isEmpty() ? raw : f.pipeline().apply(raw, context);
                values.put(name, Coercion.coerce(transformed, f.target().type()));
            } catch (TransformException | IllegalArgumentException | ArithmeticException e) {
                values.put(name, null);
                issues.add(Violation.error("MAPPING", name, f.target().label() + ": " + e.getMessage()));
            }
        }
        return new MappedRecord(source.line(), values, lineage, issues);
    }

    /** Joins several source values; a "+" join sums them as amounts (CGST + SGST + IGST). */
    private static Object combine(List<String> parts, String join) {
        if (parts.isEmpty()) {
            return null;
        }
        if ("+".equals(join)) {
            BigDecimal total = BigDecimal.ZERO;
            for (String part : parts) {
                total = total.add(Amounts.parse(part));
            }
            return total;
        }
        return parts.size() == 1 ? parts.get(0) : String.join(join, parts);
    }

    private String resolve(String column, SourceRecord source) {
        if (source.fields().containsKey(column)) {
            return column;
        }
        String cached = resolvedColumns.get(column);
        if (cached != null && source.fields().containsKey(cached)) {
            return cached;
        }
        String wanted = Texts.normalizeKey(column);
        for (String actual : source.fields().keySet()) {
            if (Texts.normalizeKey(actual).equals(wanted)) {
                resolvedColumns.put(column, actual);
                return actual;
            }
        }
        return null;
    }
}
