package dev.berth9.engine.map;

import dev.berth9.engine.model.Violation;
import dev.berth9.engine.ref.ReferenceData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Applies {@link Enrichment} steps in order (content enricher). */
public final class Enricher {

    public MappedRecord apply(MappedRecord record, List<Enrichment> steps, ReferenceData reference) {
        if (steps.isEmpty()) {
            return record;
        }
        Map<String, Object> values = new LinkedHashMap<>(record.values());
        Map<String, List<String>> lineage = new LinkedHashMap<>(record.lineage());
        List<Violation> issues = new ArrayList<>(record.issues());
        for (Enrichment step : steps) {
            switch (step) {
                case Enrichment.Lookup l -> lookup(l, values, lineage, reference);
                case Enrichment.Convert c -> convert(c, values, lineage, issues, reference);
                case Enrichment.Compute c -> compute(c, values, lineage);
            }
        }
        return new MappedRecord(record.line(), values, lineage, issues);
    }

    private static void lookup(Enrichment.Lookup l, Map<String, Object> values, Map<String, List<String>> lineage, ReferenceData reference) {
        String key = KeyTemplate.render(l.key(), values);
        if (key == null) {
            return;
        }
        Optional<Map<String, Object>> row = reference.find(l.dataset(), key);
        row.ifPresent(r -> l.copy().forEach((target, column) -> {
            Object current = values.get(target);
            if ((!l.onlyIfBlank() || current == null || current.toString().isBlank()) && r.get(column) != null) {
                values.put(target, r.get(column));
                lineage.put(target, List.of(l.dataset() + "." + column));
            }
        }));
    }

    private static void convert(Enrichment.Convert c, Map<String, Object> values, Map<String, List<String>> lineage,
                                List<Violation> issues, ReferenceData reference) {
        BigDecimal amount = asDecimal(values.get(c.amountField()));
        Object currency = values.get(c.currencyField());
        if (amount == null || currency == null) {
            return;
        }
        if (currency.toString().equalsIgnoreCase(c.baseCurrency())) {
            values.put(c.targetField(), amount);
            lineage.put(c.targetField(), List.of(c.amountField()));
            return;
        }
        Optional<Map<String, Object>> fx = reference.find("fx", currency.toString());
        if (fx.isEmpty() || fx.get().get("rate") == null) {
            issues.add(Violation.warning("FX_RATE", c.targetField(), "no FX rate for " + currency + " to " + c.baseCurrency()));
            return;
        }
        BigDecimal rate = asDecimal(fx.get().get("rate"));
        values.put(c.targetField(), amount.multiply(rate).setScale(2, RoundingMode.HALF_UP));
        lineage.put(c.targetField(), List.of(c.amountField(), "fx." + currency));
    }

    private static void compute(Enrichment.Compute c, Map<String, Object> values, Map<String, List<String>> lineage) {
        if (values.get(c.targetField()) != null) {
            return;
        }
        BigDecimal left = asDecimal(values.get(c.left()));
        BigDecimal right = asDecimal(values.get(c.right()));
        if (left == null || right == null) {
            return;
        }
        BigDecimal result = switch (c.operator()) {
            case "*" -> left.multiply(right);
            case "+" -> left.add(right);
            case "-" -> left.subtract(right);
            case "/" -> right.signum() == 0 ? null : left.divide(right, c.scale() + 4, RoundingMode.HALF_UP);
            default -> throw new IllegalArgumentException("unsupported operator " + c.operator());
        };
        if (result != null) {
            values.put(c.targetField(), result.setScale(c.scale(), RoundingMode.HALF_UP));
            lineage.put(c.targetField(), List.of("computed: " + c.left() + " " + c.operator() + " " + c.right()));
        }
    }

    static BigDecimal asDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
