package dev.berth9.engine.suggest;

import java.util.List;

/**
 * A proposed mapping for one target field.
 *
 * @param field      target field
 * @param sources    partner columns (several when values must be summed, e.g. CGST + SGST + IGST)
 * @param join       how several sources combine ("+" sums amounts)
 * @param constant   fixed value when the answer is not in the file at all (the sender, the partner's currency)
 * @param expression derivation when the field can be computed from others, e.g. {@code quantity * unitPrice}
 * @param confidence 0..1
 * @param band       HIGH (auto-accept), MEDIUM (review) or LOW (needs a human or the LLM)
 * @param transform  proposed transform pipeline
 * @param reasons    why, in plain words, so a reviewer can trust or reject it quickly
 * @param origin     "rules" for the deterministic matcher, "llm" when a language model proposed it
 */
public record Suggestion(String field, List<String> sources, String join, String constant, String expression,
                         double confidence, String band, String transform, List<String> reasons, String origin) {

    public Suggestion {
        sources = List.copyOf(sources);
        reasons = List.copyOf(reasons);
    }

    public static String band(double confidence) {
        return confidence >= 0.8 ? "HIGH" : confidence >= 0.55 ? "MEDIUM" : "LOW";
    }

    public boolean matched() {
        return !sources.isEmpty();
    }

    public boolean resolved() {
        return matched() || constant != null || expression != null;
    }
}
