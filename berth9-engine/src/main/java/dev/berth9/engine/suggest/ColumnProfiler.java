package dev.berth9.engine.suggest;

import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.model.ValueKind;
import dev.berth9.engine.text.Texts;
import dev.berth9.engine.transform.Dates;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Profiles every column of a sample of records. */
public final class ColumnProfiler {

    private ColumnProfiler() {
    }

    public static List<ColumnProfile> profile(List<String> columns, List<SourceRecord> sample) {
        Set<String> allColumns = new LinkedHashSet<>(columns);
        sample.forEach(r -> allColumns.addAll(r.fields().keySet()));
        List<ColumnProfile> profiles = new ArrayList<>();
        for (String column : allColumns) {
            profiles.add(profileColumn(column, sample));
        }
        return profiles;
    }

    static ColumnProfile profileColumn(String column, List<SourceRecord> sample) {
        Map<ValueKind, Integer> counts = new EnumMap<>(ValueKind.class);
        Set<String> distinct = new LinkedHashSet<>();
        List<String> values = new ArrayList<>();
        int filled = 0;
        for (SourceRecord r : sample) {
            String v = Texts.clean(r.get(column));
            if (v == null) {
                continue;
            }
            filled++;
            values.add(v);
            distinct.add(v);
            for (ValueKind kind : ValueClassifier.classify(v)) {
                counts.merge(kind, 1, Integer::sum);
            }
        }
        Map<ValueKind, Double> shares = new EnumMap<>(ValueKind.class);
        int denominator = filled;
        counts.forEach((kind, n) -> shares.put(kind, denominator == 0 ? 0.0 : n / (double) denominator));
        List<String> patterns = shares.getOrDefault(ValueKind.DATE, 0.0) >= 0.8 ? Dates.inferPatterns(values) : List.of();
        List<String> examples = distinct.stream().limit(5).toList();
        return new ColumnProfile(column, sample.size(), filled, distinct.size(), examples, shares, patterns);
    }
}
