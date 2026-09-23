package dev.berth9.engine.model;

import java.util.List;
import java.util.Optional;

/**
 * A canonical document type every partner format is mapped into (e.g. an invoice line).
 *
 * @param name        schema id, e.g. {@code invoice-line}
 * @param label       display name
 * @param fields      ordered fields
 * @param businessKey fields that identify a record downstream (used for idempotent delivery)
 */
public record TargetSchema(String name, String label, List<TargetField> fields, List<String> businessKey) {

    public TargetSchema {
        fields = List.copyOf(fields);
        businessKey = businessKey == null ? List.of() : List.copyOf(businessKey);
        label = label == null || label.isBlank() ? name : label;
    }

    public Optional<TargetField> field(String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
    }

    public List<TargetField> requiredFields() {
        return fields.stream().filter(TargetField::required).toList();
    }
}
