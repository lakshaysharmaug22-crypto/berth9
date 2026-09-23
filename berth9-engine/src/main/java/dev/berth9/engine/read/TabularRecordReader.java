package dev.berth9.engine.read;

import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.text.Texts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * Turns raw rows into records for any tabular source: finds the header (skipping banners and
 * merging two-row headers), then streams data rows while dropping blank rows, footer totals
 * and headers repeated at page breaks.
 */
public class TabularRecordReader implements RecordReader {

    private static final Pattern FOOTER = Pattern.compile(
            "(?i)^(grand\\s*total|sub\\s*-?\\s*total|total|end\\s+of\\s+(report|file|data)|\\*{3,}).*");

    private final RowSource source;
    private final Deque<Row> pending = new ArrayDeque<>();
    private final List<String> columns;
    private final List<String> headerKeys;
    private SourceRecord next;
    private boolean done;

    public TabularRecordReader(RowSource source, Integer headerRow) {
        this.source = source;
        List<Row> buffer = new ArrayList<>();
        Row row;
        while (buffer.size() < HeaderLocator.SCAN_DEPTH && (row = read()) != null) {
            buffer.add(row);
        }
        List<List<String>> cells = buffer.stream().map(Row::cells).toList();
        int headerIndex = headerRow != null ? indexOf(buffer, headerRow) : HeaderLocator.locate(cells);
        if (headerIndex < 0) {
            int width = cells.stream().mapToInt(List::size).max().orElse(0);
            List<String> generated = new ArrayList<>();
            for (int i = 1; i <= width; i++) {
                generated.add("column_" + i);
            }
            this.columns = List.copyOf(generated);
            this.headerKeys = List.of();
            pending.addAll(buffer);
            return;
        }
        List<String> header = cells.get(headerIndex);
        List<String> group = null;
        int dataStart = headerIndex + 1;
        List<String> afterNext = firstNonBlankAfter(cells, headerIndex + 1);
        if (headerIndex + 1 < cells.size()
                && HeaderLocator.isSubHeader(header, cells.get(headerIndex + 1), afterNext)) {
            group = header;
            header = cells.get(headerIndex + 1);
            dataStart = headerIndex + 2;
        } else if (headerIndex > 0 && HeaderLocator.isGroupRow(cells.get(headerIndex - 1), header)) {
            group = cells.get(headerIndex - 1);
        }
        this.columns = List.copyOf(HeaderLocator.columnNames(group, header));
        this.headerKeys = header.stream().map(Texts::normalizeKey).toList();
        for (int i = dataStart; i < buffer.size(); i++) {
            pending.add(buffer.get(i));
        }
    }

    @Override
    public List<String> columns() {
        return columns;
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
        if (next == null) {
            done = true;
        }
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
        while (true) {
            Row row = pending.isEmpty() ? read() : pending.poll();
            if (row == null) {
                return null;
            }
            if (row.isBlank() || isFooter(row) || isRepeatedHeader(row)) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            List<String> cells = row.cells();
            for (int i = 0; i < Math.max(columns.size(), cells.size()); i++) {
                String name = i < columns.size() ? columns.get(i) : "column_" + (i + 1);
                String value = i < cells.size() && cells.get(i) != null ? cells.get(i).replace(' ', ' ').strip() : "";
                if (i >= columns.size() && value.isEmpty()) {
                    continue;
                }
                fields.put(name, value);
            }
            return new SourceRecord(row.number(), fields);
        }
    }

    private boolean isFooter(Row row) {
        for (String cell : row.cells()) {
            if (!Texts.isBlank(cell)) {
                return FOOTER.matcher(cell.strip()).matches();
            }
        }
        return false;
    }

    private boolean isRepeatedHeader(Row row) {
        if (headerKeys.isEmpty()) {
            return false;
        }
        int compared = 0;
        int same = 0;
        for (int i = 0; i < Math.min(headerKeys.size(), row.cells().size()); i++) {
            String key = headerKeys.get(i);
            if (key.isEmpty()) {
                continue;
            }
            compared++;
            if (key.equals(Texts.normalizeKey(row.cells().get(i)))) {
                same++;
            }
        }
        return compared >= 2 && same >= compared * 0.8;
    }

    private Row read() {
        try {
            return source.next();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int indexOf(List<Row> rows, int physicalRow) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).number() == physicalRow) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> firstNonBlankAfter(List<List<String>> rows, int index) {
        for (int i = index + 1; i < rows.size(); i++) {
            if (HeaderLocator.nonBlank(rows.get(i)) > 0) {
                return rows.get(i);
            }
        }
        return null;
    }

    @Override
    public void close() {
        source.close();
    }
}
