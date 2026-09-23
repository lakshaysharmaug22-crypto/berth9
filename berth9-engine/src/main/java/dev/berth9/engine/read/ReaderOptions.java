package dev.berth9.engine.read;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Per-partner knobs for reading a file. Every option is optional; readers auto-detect what they can.
 *
 * @param delimiter        CSV delimiter, sniffed when null
 * @param headerRow        1-based header row for tabular files, located automatically when null
 * @param sheet            XLSX sheet name, first sheet when null
 * @param recordElement    XML element that represents one record (e.g. {@code InvoiceDetailItem})
 * @param recordSegment    EDI segment that starts one record loop (e.g. {@code IT1} or {@code PO1})
 * @param fixedLayouts     fixed-width layouts keyed by record type prefix ({@code H}, {@code D}, {@code T})
 * @param detailRecordType fixed-width record type that produces records; other types become context
 * @param trailerRecordType fixed-width record type carrying control totals
 * @param charset          text encoding, UTF-8 when null
 */
public record ReaderOptions(Character delimiter,
                            Integer headerRow,
                            String sheet,
                            String recordElement,
                            String recordSegment,
                            Map<String, List<FixedColumn>> fixedLayouts,
                            String detailRecordType,
                            String trailerRecordType,
                            String charset) {

    public ReaderOptions {
        fixedLayouts = fixedLayouts == null ? Map.of() : Map.copyOf(fixedLayouts);
    }

    public static ReaderOptions defaults() {
        return new ReaderOptions(null, null, null, null, null, null, null, null, null);
    }

    public Charset charsetOrDefault() {
        return charset == null || charset.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(charset);
    }

    public ReaderOptions withRecordSegment(String segment) {
        return new ReaderOptions(delimiter, headerRow, sheet, recordElement, segment, fixedLayouts, detailRecordType, trailerRecordType, charset);
    }

    public ReaderOptions withRecordElement(String element) {
        return new ReaderOptions(delimiter, headerRow, sheet, element, recordSegment, fixedLayouts, detailRecordType, trailerRecordType, charset);
    }
}
