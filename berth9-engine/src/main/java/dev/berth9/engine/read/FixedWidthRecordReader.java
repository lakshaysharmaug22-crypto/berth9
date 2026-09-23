package dev.berth9.engine.read;

import dev.berth9.engine.model.SourceRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Reads hierarchical fixed-width extracts from legacy ERPs: header (H), detail (D) and trailer (T)
 * records identified by a leading record-type code. Header fields are carried onto every detail
 * record that follows (prefixed with the record type), and the trailer's RECORD_COUNT becomes a
 * control total the pipeline reconciles against what was actually read.
 */
public final class FixedWidthRecordReader implements RecordReader {

    private final BufferedReader in;
    private final Map<String, List<FixedColumn>> layouts;
    private final String detailType;
    private final String trailerType;
    private final int typeLength;
    private final Map<String, String> context = new LinkedHashMap<>();
    private final Map<String, String> controlTotals = new LinkedHashMap<>();
    private final Set<String> columns = new LinkedHashSet<>();
    private long lineNo;
    private SourceRecord next;
    private boolean done;

    public FixedWidthRecordReader(BufferedReader in, ReaderOptions options) {
        if (options.fixedLayouts().isEmpty()) {
            throw new IllegalArgumentException("fixed-width files need a record layout in the mapping spec");
        }
        this.in = in;
        this.layouts = options.fixedLayouts();
        this.detailType = options.detailRecordType() != null ? options.detailRecordType()
                : layouts.size() == 1 ? layouts.keySet().iterator().next() : "D";
        this.trailerType = options.trailerRecordType();
        this.typeLength = layouts.keySet().stream().mapToInt(String::length).max().orElse(1);
        layouts.forEach((type, cols) -> cols.forEach(c ->
                columns.add(type.equals(detailType) ? c.name() : type + "." + c.name())));
    }

    @Override
    public List<String> columns() {
        return List.copyOf(columns);
    }

    @Override
    public Map<String, String> controlTotals() {
        return Collections.unmodifiableMap(controlTotals);
    }

    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }
        if (done) {
            return false;
        }
        next = advance();
        done = next == null;
        return next != null;
    }

    @Override
    public SourceRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        SourceRecord r = next;
        next = null;
        return r;
    }

    private SourceRecord advance() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                String type = recordType(line);
                List<FixedColumn> layout = layouts.get(type);
                if (layout == null) {
                    continue;
                }
                if (type.equals(detailType)) {
                    Map<String, String> fields = new LinkedHashMap<>(context);
                    for (FixedColumn column : layout) {
                        fields.put(column.name(), column.slice(line));
                    }
                    return new SourceRecord(lineNo, fields);
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (FixedColumn column : layout) {
                    values.put(column.name(), column.slice(line));
                }
                if (type.equals(trailerType)) {
                    String count = values.get("RECORD_COUNT");
                    if (count != null && !count.isBlank()) {
                        controlTotals.merge("lineCount", count.replaceFirst("^0+(?=\\d)", ""),
                                (a, b) -> String.valueOf(Long.parseLong(a) + Long.parseLong(b)));
                    }
                } else {
                    context.entrySet().removeIf(e -> e.getKey().startsWith(type + "."));
                    values.forEach((k, v) -> context.put(type + "." + k, v));
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String recordType(String line) {
        for (int len = typeLength; len >= 1; len--) {
            if (line.length() >= len && layouts.containsKey(line.substring(0, len))) {
                return line.substring(0, len);
            }
        }
        return "";
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
