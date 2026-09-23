package dev.berth9.engine.read;

import dev.berth9.engine.format.FormatSniffer;
import dev.berth9.engine.format.SourceFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of readers by format. The engine ships CSV, fixed-width, XML, X12 and JSON; the server
 * registers XLSX (Apache POI) so this module stays dependency-free.
 */
public final class RecordReaders {

    private static final int SNIFF_LINES = 40;
    private static final int MARK_LIMIT = 1 << 20;

    private final Map<SourceFormat, ReaderFactory> factories = new EnumMap<>(SourceFormat.class);

    public static RecordReaders withDefaults() {
        RecordReaders readers = new RecordReaders();
        readers.register(SourceFormat.CSV, RecordReaders::openCsv);
        readers.register(SourceFormat.FIXED_WIDTH, (in, o) ->
                new FixedWidthRecordReader(new BufferedReader(new InputStreamReader(in, o.charsetOrDefault())), o));
        readers.register(SourceFormat.XML, XmlRecordReader::new);
        readers.register(SourceFormat.X12, X12RecordReader::new);
        readers.register(SourceFormat.JSON, JsonRecordReader::new);
        return readers;
    }

    public RecordReaders register(SourceFormat format, ReaderFactory factory) {
        factories.put(format, factory);
        return this;
    }

    public boolean supports(SourceFormat format) {
        return factories.containsKey(format);
    }

    public Set<SourceFormat> formats() {
        return factories.keySet();
    }

    public RecordReader open(SourceFormat format, InputStream in, ReaderOptions options) throws IOException {
        ReaderFactory factory = factories.get(format);
        if (factory == null) {
            throw new IllegalArgumentException("no reader registered for format " + format);
        }
        return factory.open(in, options == null ? ReaderOptions.defaults() : options);
    }

    static RecordReader openCsv(InputStream in, ReaderOptions options) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, options.charsetOrDefault()), 1 << 16);
        char delimiter;
        if (options.delimiter() != null) {
            delimiter = options.delimiter();
        } else {
            reader.mark(MARK_LIMIT);
            List<String> sample = new ArrayList<>();
            String line;
            while (sample.size() < SNIFF_LINES && (line = reader.readLine()) != null) {
                sample.add(line);
            }
            reader.reset();
            delimiter = FormatSniffer.sniffDelimiter(sample);
        }
        return new TabularRecordReader(new CsvRowSource(reader, delimiter), options.headerRow());
    }
}
