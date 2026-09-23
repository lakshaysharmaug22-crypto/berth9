package dev.berth9.engine.suggest;

import dev.berth9.engine.model.ValueKind;

import java.util.List;
import java.util.Map;

/**
 * What a partner column looks like, computed from a sample of rows.
 *
 * @param column       column name as it appears in the file
 * @param sampled      rows looked at
 * @param filled       rows with a non-blank value
 * @param distinct     distinct non-blank values
 * @param samples      up to five example values
 * @param kinds        share of non-blank values having each kind (0..1)
 * @param datePatterns date patterns that parse every sample, when the column holds dates
 */
public record ColumnProfile(String column, int sampled, int filled, int distinct, List<String> samples,
                            Map<ValueKind, Double> kinds, List<String> datePatterns) {

    public ColumnProfile {
        samples = List.copyOf(samples);
        kinds = Map.copyOf(kinds);
        datePatterns = List.copyOf(datePatterns);
    }

    public double share(ValueKind kind) {
        return kinds.getOrDefault(kind, 0.0);
    }

    public double fillRate() {
        return sampled == 0 ? 0 : filled / (double) sampled;
    }
}
