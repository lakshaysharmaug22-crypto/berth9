package dev.berth9.engine.suggest;

import dev.berth9.engine.model.FieldType;
import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.model.TargetField;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.model.ValueKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic first pass of mapping suggestions: header similarity (60%) plus how well the column's
 * values fit the field's type (40%), then a one-to-one assignment, highest score first. It is fast,
 * explainable and free; only fields it cannot settle are worth sending to a language model.
 */
public final class MappingSuggester {

    private static final double NAME_WEIGHT = 0.6;
    private static final double FIT_WEIGHT = 0.4;
    private static final double MIN_SCORE = 0.35;
    private static final double MIN_NAME = 0.3;
    private static final List<ValueKind> STRONG_KINDS = List.of(ValueKind.GSTIN, ValueKind.CURRENCY, ValueKind.UOM);

    private record Candidate(TargetField field, ColumnProfile column, double name, double fit, double score) {
    }

    /**
     * @param localeHint "IN" or "US" to settle day/month order when every sample date is ambiguous
     */
    public SuggestionReport suggest(TargetSchema schema, List<String> columns, List<SourceRecord> sample, String localeHint) {
        List<ColumnProfile> profiles = ColumnProfiler.profile(columns, sample);
        List<Candidate> candidates = new ArrayList<>();
        List<TargetField> mappable = schema.fields().stream().filter(f -> !f.derived()).toList();
        for (TargetField field : mappable) {
            for (ColumnProfile profile : profiles) {
                double name = HeaderMatcher.score(profile.column(), field);
                double fit = fit(field, profile);
                if (name < MIN_NAME && !strongKind(field, profile)) {
                    continue;
                }
                double score = combine(field, profile, name, fit);
                if (score >= MIN_SCORE) {
                    candidates.add(new Candidate(field, profile, name, fit, score));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        Map<String, Candidate> chosen = new LinkedHashMap<>();
        Set<String> usedColumns = new HashSet<>();
        for (Candidate c : candidates) {
            if (chosen.containsKey(c.field().name()) || usedColumns.contains(c.column().column())) {
                continue;
            }
            chosen.put(c.field().name(), c);
            usedColumns.add(c.column().column());
        }
        Map<String, List<ColumnProfile>> sums = siblingsToSum(chosen, candidates, usedColumns);

        List<Suggestion> suggestions = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (TargetField field : mappable) {
            Candidate c = chosen.get(field.name());
            if (c == null) {
                suggestions.add(new Suggestion(field.name(), List.of(), null, null, null, 0, "LOW", null,
                        List.of("no column looks like " + field.label()), "rules"));
                if (field.required()) {
                    missing.add(field.name());
                }
                continue;
            }
            List<String> reasons = new ArrayList<>();
            reasons.add(String.format(Locale.ROOT, "header '%s' ~ '%s' (%.2f)", c.column().column(), field.label(), c.name()));
            reasons.add(fitReason(field, c.column(), c.fit()));
            List<String> sources = new ArrayList<>(List.of(c.column().column()));
            String join = null;
            List<ColumnProfile> extra = sums.get(field.name());
            if (extra != null) {
                extra.forEach(p -> sources.add(p.column()));
                join = "+";
                reasons.add("sums " + String.join(" + ", sources) + " (same header group)");
            }
            String transform = transformFor(field, c.column(), localeHint, reasons);
            double confidence = Math.round(c.score() * 100) / 100.0;
            if (field.required() && confidence < 0.55) {
                missing.add(field.name());
            }
            suggestions.add(new Suggestion(field.name(), sources, join, null, null, confidence, Suggestion.band(confidence), transform, reasons, "rules"));
        }
        Set<String> claimed = new HashSet<>();
        suggestions.forEach(s -> claimed.addAll(s.sources()));
        List<String> unused = profiles.stream().map(ColumnProfile::column).filter(col -> !claimed.contains(col)).toList();
        return new SuggestionReport(suggestions, unused, missing, profiles);
    }

    static double combine(TargetField field, ColumnProfile profile, double name, double fit) {
        if (profile.filled() == 0) {
            return name * 0.6;
        }
        double score = NAME_WEIGHT * name + FIT_WEIGHT * fit;
        if (name >= 0.95 && fit >= 0.5) {
            score = Math.max(score, 0.92);
        }
        if (fit < 0.2 && name < 0.95) {
            score *= 0.5;
        }
        if (strongKind(field, profile)) {
            score = Math.max(score, 0.8);
        }
        return Math.min(score, 1.0);
    }

    /** Values so distinctive (a valid GSTIN, an ISO currency, a unit code) that the header hardly matters. */
    static boolean strongKind(TargetField field, ColumnProfile profile) {
        for (ValueKind strong : STRONG_KINDS) {
            if (field.kinds().contains(strong) && profile.share(strong) >= 0.9) {
                return true;
            }
        }
        return false;
    }

    /** Share of the column's values that have a shape this field accepts. */
    static double fit(TargetField field, ColumnProfile profile) {
        if (profile.filled() == 0) {
            return 0;
        }
        Set<ValueKind> accepted = field.kinds();
        if (accepted.isEmpty()) {
            accepted = switch (field.type()) {
                case DATE -> Set.of(ValueKind.DATE);
                case DECIMAL -> Set.of(ValueKind.AMOUNT, ValueKind.DECIMAL);
                case INTEGER -> Set.of(ValueKind.INTEGER);
                case BOOLEAN -> Set.of(ValueKind.TEXT);
                case STRING -> Set.of(ValueKind.TEXT, ValueKind.CODE);
            };
        }
        double best = 0;
        for (ValueKind kind : accepted) {
            best = Math.max(best, profile.share(kind));
        }
        if (field.type() != FieldType.DATE && profile.share(ValueKind.DATE) >= 0.8 && !accepted.contains(ValueKind.DATE)) {
            best *= 0.3;
        }
        return best;
    }

    /**
     * Numeric fields whose best column sits in a header group with siblings that also match strongly
     * ("Tax (INR) CGST", "... SGST", "... IGST") get those siblings summed in.
     */
    private static Map<String, List<ColumnProfile>> siblingsToSum(Map<String, Candidate> chosen, List<Candidate> candidates, Set<String> used) {
        Map<String, List<ColumnProfile>> sums = new HashMap<>();
        for (Candidate c : chosen.values()) {
            if (c.field().type() != FieldType.DECIMAL) {
                continue;
            }
            String group = groupPrefix(c.column().column());
            if (group == null) {
                continue;
            }
            List<ColumnProfile> siblings = new ArrayList<>();
            for (Candidate other : candidates) {
                if (other.field() == c.field() && other != c && !used.contains(other.column().column())
                        && group.equals(groupPrefix(other.column().column())) && other.name() >= 0.8 && other.fit() >= 0.8) {
                    siblings.add(other.column());
                }
            }
            if (!siblings.isEmpty()) {
                siblings.forEach(s -> used.add(s.column()));
                sums.put(c.field().name(), siblings);
            }
        }
        return sums;
    }

    private static String groupPrefix(String column) {
        int paren = column.indexOf(')');
        if (paren > 0 && paren < column.length() - 1) {
            return column.substring(0, paren + 1).strip().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String fitReason(TargetField field, ColumnProfile profile, double fit) {
        if (profile.filled() == 0) {
            return "column is empty in the sample";
        }
        String example = profile.samples().isEmpty() ? "" : " (e.g. '" + profile.samples().get(0) + "')";
        return String.format(Locale.ROOT, "%.0f%% of sampled values look like %s%s", fit * 100, describe(field), example);
    }

    private static String describe(TargetField field) {
        Set<ValueKind> kinds = field.kinds();
        if (field.type() == FieldType.DATE) {
            return "dates";
        }
        if (kinds.contains(ValueKind.GSTIN)) {
            return "GSTINs";
        }
        if (kinds.contains(ValueKind.CURRENCY)) {
            return "currency codes";
        }
        if (kinds.contains(ValueKind.UOM)) {
            return "unit codes";
        }
        if (kinds.contains(ValueKind.AMOUNT)) {
            return "amounts";
        }
        if (field.type() == FieldType.INTEGER || field.type() == FieldType.DECIMAL) {
            return "numbers";
        }
        if (kinds.contains(ValueKind.CODE)) {
            return "codes";
        }
        return "text";
    }

    private static String transformFor(TargetField field, ColumnProfile profile, String localeHint, List<String> reasons) {
        Set<ValueKind> kinds = field.kinds();
        if (field.type() == FieldType.DATE) {
            List<String> patterns = profile.datePatterns();
            if (patterns.isEmpty()) {
                reasons.add("date format could not be inferred from the sample");
                return "date";
            }
            if (patterns.contains("iso")) {
                return "date";
            }
            boolean dayFirst = patterns.contains("dd/MM/yyyy") || patterns.contains("d/M/yyyy") || patterns.contains("dd-MM-yyyy");
            boolean monthFirst = patterns.contains("MM/dd/yyyy") || patterns.contains("M/d/yyyy") || patterns.contains("MM-dd-yyyy");
            if (dayFirst && monthFirst) {
                boolean us = "US".equalsIgnoreCase(localeHint);
                String chosen = pick(patterns, us ? List.of("MM/dd/yyyy", "M/d/yyyy", "MM-dd-yyyy") : List.of("dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy"));
                reasons.add("every sample date is ambiguous (day <= 12); assumed " + (us ? "month-first (US partner)" : "day-first (IN partner)"));
                return "date(" + chosen + ")";
            }
            return "date(" + patterns.get(0) + ")";
        }
        if (kinds.contains(ValueKind.CURRENCY)) {
            return "currency";
        }
        if (kinds.contains(ValueKind.UOM)) {
            return "upper | lookup(uom, *)";
        }
        if (field.type() == FieldType.DECIMAL) {
            return kinds.contains(ValueKind.AMOUNT) ? "amount" : "number";
        }
        if (field.type() == FieldType.INTEGER) {
            return "number";
        }
        if (kinds.contains(ValueKind.GSTIN) || kinds.contains(ValueKind.CODE)) {
            return "trim | upper";
        }
        if (kinds.contains(ValueKind.TEXT)) {
            return "collapse";
        }
        return "trim";
    }

    private static String pick(List<String> available, List<String> preferred) {
        for (String p : preferred) {
            if (available.contains(p)) {
                return p;
            }
        }
        return available.get(0);
    }
}
