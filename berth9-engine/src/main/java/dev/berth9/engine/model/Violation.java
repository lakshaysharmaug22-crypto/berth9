package dev.berth9.engine.model;

/**
 * A single problem found in a record.
 *
 * @param ruleId   id of the rule that fired (or TRANSFORM / TYPE for mapping failures)
 * @param field    target field the problem is about, may be null for record-level issues
 * @param severity ERROR or WARNING
 * @param message  sentence an operator can act on
 */
public record Violation(String ruleId, String field, Severity severity, String message) {

    public static Violation error(String ruleId, String field, String message) {
        return new Violation(ruleId, field, Severity.ERROR, message);
    }

    public static Violation warning(String ruleId, String field, String message) {
        return new Violation(ruleId, field, Severity.WARNING, message);
    }
}
