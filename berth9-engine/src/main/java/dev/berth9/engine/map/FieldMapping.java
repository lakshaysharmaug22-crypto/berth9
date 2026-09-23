package dev.berth9.engine.map;

import java.util.List;

/**
 * How one target field is produced from a partner record.
 *
 * @param target    target field name
 * @param sources   partner columns to read; several are joined (e.g. first + last name)
 * @param join      separator used when joining several sources, a space by default
 * @param constant  fixed value instead of sources (e.g. a currency the partner never states)
 * @param transform transform pipeline expression, e.g. {@code trim | amount}
 */
public record FieldMapping(String target, List<String> sources, String join, String constant, String transform) {

    public FieldMapping {
        sources = sources == null ? List.of() : List.copyOf(sources);
        join = join == null ? " " : join;
    }

    public static FieldMapping of(String target, String source, String transform) {
        return new FieldMapping(target, List.of(source), null, null, transform);
    }
}
