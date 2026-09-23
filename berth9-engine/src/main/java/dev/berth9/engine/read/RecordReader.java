package dev.berth9.engine.read;

import dev.berth9.engine.model.SourceRecord;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Streams records out of a partner file one at a time, so a 2 GB file never has to fit in memory.
 */
public interface RecordReader extends Iterator<SourceRecord>, AutoCloseable {

    /** Column names known so far. Tabular formats know them up front; XML/EDI grow as records are read. */
    List<String> columns();

    /**
     * Totals the file declares about itself (EDI CTT01, a fixed-width trailer count), keyed
     * {@code lineCount}. Complete once the reader is exhausted.
     */
    default Map<String, String> controlTotals() {
        return Map.of();
    }

    /** File-level facts worth keeping on the job (EDI sender/receiver, control numbers). */
    default Map<String, String> fileContext() {
        return Map.of();
    }

    @Override
    void close();

    default Stream<SourceRecord> stream() {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(this, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(this::close);
    }
}
