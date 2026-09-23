package dev.berth9.engine.read;

import dev.berth9.engine.edi.X12Flattener;
import dev.berth9.engine.edi.X12Group;
import dev.berth9.engine.edi.X12Interchange;
import dev.berth9.engine.edi.X12Parser;
import dev.berth9.engine.edi.X12Transaction;
import dev.berth9.engine.model.SourceRecord;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads EDI X12 files. Interchanges are parsed whole (partner EDI files are typically kilobytes to a
 * few MB), then each accepted transaction set is flattened into records. Transaction sets that fail
 * envelope checks are skipped here; they are rejected in the 997 instead of half-processed.
 */
public final class X12RecordReader implements RecordReader {

    private final X12Interchange interchange;
    private final Iterator<SourceRecord> records;
    private final Set<String> columns = new LinkedHashSet<>();
    private final Map<String, String> controlTotals = new LinkedHashMap<>();
    private final Map<String, String> context = new LinkedHashMap<>();

    public X12RecordReader(InputStream in, ReaderOptions options) throws IOException {
        this(new String(in.readAllBytes(), options.charsetOrDefault()), options);
    }

    public X12RecordReader(String content, ReaderOptions options) {
        this.interchange = new X12Parser().parse(content);
        X12Flattener flattener = new X12Flattener();
        List<SourceRecord> all = new ArrayList<>();
        long declaredLines = 0;
        boolean anyDeclared = false;
        context.put("interchangeControl", interchange.controlNumber());
        context.put("sender", interchange.senderId());
        context.put("receiver", interchange.receiverId());
        context.put("usage", interchange.usage());
        for (X12Group group : interchange.groups()) {
            for (X12Transaction tx : group.transactions()) {
                if (!tx.accepted()) {
                    continue;
                }
                String segment = options.recordSegment() != null ? options.recordSegment() : defaultRecordSegment(tx.type());
                Map<String, String> envelope = new LinkedHashMap<>();
                envelope.put("ISA06", interchange.senderId());
                envelope.put("ISA08", interchange.receiverId());
                envelope.put("ISA13", interchange.controlNumber());
                envelope.put("GS01", group.functionalId());
                envelope.put("GS02", group.senderCode());
                envelope.put("GS06", group.controlNumber());
                X12Flattener.Flattened flat = flattener.flatten(tx, segment, envelope);
                all.addAll(flat.records());
                String ctt = flat.summary().get("CTT01");
                if (ctt != null) {
                    anyDeclared = true;
                    declaredLines += Long.parseLong(ctt.strip());
                }
            }
        }
        if (anyDeclared) {
            controlTotals.put("lineCount", String.valueOf(declaredLines));
        }
        all.forEach(r -> columns.addAll(r.fields().keySet()));
        this.records = all.iterator();
    }

    static String defaultRecordSegment(String transactionType) {
        return switch (transactionType) {
            case "850" -> "PO1";
            default -> "IT1";
        };
    }

    public X12Interchange interchange() {
        return interchange;
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
    public Map<String, String> fileContext() {
        return Collections.unmodifiableMap(context);
    }

    @Override
    public boolean hasNext() {
        return records.hasNext();
    }

    @Override
    public SourceRecord next() {
        return records.next();
    }

    @Override
    public void close() {
        // fully read at construction
    }
}
