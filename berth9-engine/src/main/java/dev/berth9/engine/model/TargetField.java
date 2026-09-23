package dev.berth9.engine.model;

import java.util.List;
import java.util.Set;

/**
 * A field of a canonical document type.
 *
 * @param name     machine name, camelCase (e.g. {@code unitPrice})
 * @param type     data type the mapped value is coerced to
 * @param required whether a record is invalid without it
 * @param label    human label shown in the console
 * @param aliases  header phrases partners commonly use for this field ("rate", "price per unit")
 * @param kinds    value shapes that fit this field; drives the type-fit part of mapping suggestions
 * @param derived  computed by the platform (e.g. a base-currency amount), never mapped from a partner column
 */
public record TargetField(String name, FieldType type, boolean required, String label,
                          List<String> aliases, Set<ValueKind> kinds, boolean derived) {

    public TargetField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field name is required");
        }
        type = type == null ? FieldType.STRING : type;
        label = label == null || label.isBlank() ? name : label;
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
    }
}
