package dev.berth9.engine.map;

import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.read.ReaderOptions;

import java.util.List;

/**
 * A partner's versioned mapping: how to read their file and how each canonical field is built.
 * Versions are immutable once saved; files record the version they were processed with.
 */
public record MappingSpec(String partnerId,
                          int version,
                          String schema,
                          SourceFormat format,
                          ReaderOptions reader,
                          List<FieldMapping> fields,
                          List<Enrichment> enrichments) {

    public MappingSpec {
        reader = reader == null ? ReaderOptions.defaults() : reader;
        fields = List.copyOf(fields);
        enrichments = enrichments == null ? List.of() : List.copyOf(enrichments);
    }
}
