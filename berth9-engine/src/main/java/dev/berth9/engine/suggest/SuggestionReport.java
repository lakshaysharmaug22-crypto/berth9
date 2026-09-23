package dev.berth9.engine.suggest;

import java.util.List;

/**
 * Output of the mapping suggester for one sample file.
 *
 * @param suggestions     one per target field, matched or not
 * @param unusedColumns   partner columns no field claimed (often notes, internal codes)
 * @param missingRequired required fields without a confident match
 * @param profiles        column profiles the decision was based on
 */
public record SuggestionReport(List<Suggestion> suggestions, List<String> unusedColumns,
                               List<String> missingRequired, List<ColumnProfile> profiles) {

    public SuggestionReport {
        suggestions = List.copyOf(suggestions);
        unusedColumns = List.copyOf(unusedColumns);
        missingRequired = List.copyOf(missingRequired);
        profiles = List.copyOf(profiles);
    }

    /** Fields the deterministic pass could not settle; the only ones worth sending to a language model. */
    public List<Suggestion> needsReview() {
        return suggestions.stream().filter(s -> !"HIGH".equals(s.band())).toList();
    }
}
