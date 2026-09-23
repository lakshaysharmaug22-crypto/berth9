package dev.berth9.engine.rules;

import dev.berth9.engine.model.Severity;

import java.math.BigDecimal;
import java.util.List;

/**
 * Declarative validation rules, loaded from JSON per document type. The set is closed (sealed) so the
 * {@link RuleEngine} switch is checked for exhaustiveness by the compiler when a new rule type is added.
 */
public sealed interface Rule {

    String id();

    Severity severity();

    /** Field must have a value. */
    record Required(String id, String field, Severity severity) implements Rule {
    }

    /** Field must match a regular expression. */
    record Matches(String id, String field, String regex, String message, Severity severity) implements Rule {
    }

    /** Numeric field must be within [min, max]; either bound may be null. */
    record Range(String id, String field, BigDecimal min, BigDecimal max, Severity severity) implements Rule {
    }

    /** Date must not be more than maxPastDays old or maxFutureDays ahead. */
    record DateWindow(String id, String field, Integer maxPastDays, Integer maxFutureDays, Severity severity) implements Rule {
    }

    /** Field must be one of the allowed values (case-insensitive). */
    record OneOf(String id, String field, List<String> allowed, Severity severity) implements Rule {
        public OneOf {
            allowed = List.copyOf(allowed);
        }
    }

    /** Combination of fields must be unique within the file (duplicate invoice lines). */
    record UniqueInFile(String id, List<String> fields, Severity severity) implements Rule {
        public UniqueInFile {
            fields = List.copyOf(fields);
        }
    }

    /** field ≈ left (op) right within an absolute or percentage tolerance, e.g. lineAmount = quantity × unitPrice. */
    record Arithmetic(String id, String field, String left, String operator, String right,
                      BigDecimal tolerance, BigDecimal tolerancePct, Severity severity) implements Rule {
    }

    /** Indian GST registration number: format plus mod-36 check digit. */
    record Gstin(String id, String field, Severity severity) implements Rule {
    }

    /**
     * Looks up a reference dataset by a key template. With {@code mustExist} the key must be found
     * (the PO line exists); without it the key must NOT be found (the invoice line was not delivered before).
     * When found, {@code checks} compare record fields to the reference row.
     */
    record Reference(String id, String dataset, String key, boolean mustExist, String message,
                     List<RefCheck> checks, Severity severity) implements Rule {
        public Reference {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    /** Applies nested rules only when a field has one of the given values (e.g. GST rules for INR invoices). */
    record When(String id, String field, List<String> equalsAny, List<Rule> then, Severity severity) implements Rule {
        public When {
            equalsAny = List.copyOf(equalsAny);
            then = List.copyOf(then);
        }
    }
}
