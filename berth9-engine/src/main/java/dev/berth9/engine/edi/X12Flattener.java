package dev.berth9.engine.edi;

import dev.berth9.engine.model.SourceRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flattens an X12 transaction set into one record per detail loop (IT1 for an 810 invoice, PO1 for
 * an 850 order). Header segments before the first loop (BIG, BEG, REF, DTM, N1, CUR...) are copied onto
 * every record; summary segments after the loops (TDS, CTT) become control totals.
 *
 * <p>Keys follow X12 position notation: {@code BIG02}, {@code IT104}. Segments whose first element is a
 * qualifier are keyed by it, so {@code N1*ST*...} becomes {@code N1.ST.02} and {@code REF*PO*...}
 * becomes {@code REF.PO.02}. Product id pairs on IT1/PO1 are expanded: {@code IT1*...*VP*KF-100}
 * gives {@code IT1.VP = KF-100}.
 */
public final class X12Flattener {

    private static final Set<String> QUALIFIED = Set.of("N1", "NM1", "REF", "DTM", "PER", "AMT", "CUR", "TXI", "SAC", "N9", "QTY");
    private static final Set<String> SUMMARY_START = Set.of("TDS", "CTT");
    private static final Set<String> PAIRED = Set.of("IT1", "PO1");

    /** Result of flattening one transaction. */
    public record Flattened(List<SourceRecord> records, Map<String, String> summary) {
    }

    public Flattened flatten(X12Transaction tx, String recordSegment, Map<String, String> envelope) {
        Map<String, String> header = new LinkedHashMap<>(envelope);
        header.put("ST01", tx.type());
        header.put("ST02", tx.controlNumber());
        Map<String, String> summary = new LinkedHashMap<>();
        List<Map<String, String>> loops = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        Map<String, String> current = null;
        boolean inSummary = false;
        for (X12Segment s : tx.segments()) {
            if (s.id().equals("ST") || s.id().equals("SE")) {
                continue;
            }
            if (!inSummary && SUMMARY_START.contains(s.id())) {
                inSummary = true;
            }
            if (inSummary) {
                put(summary, s);
                continue;
            }
            if (s.id().equals(recordSegment)) {
                current = new LinkedHashMap<>();
                loops.add(current);
                positions.add(s.position());
                put(current, s);
                if (PAIRED.contains(s.id())) {
                    for (int i = 6; i + 1 <= s.size(); i += 2) {
                        String qualifier = s.element(i).strip();
                        String value = s.element(i + 1).strip();
                        if (!qualifier.isEmpty() && !value.isEmpty()) {
                            current.putIfAbsent(s.id() + "." + qualifier, value);
                        }
                    }
                }
            } else if (current == null) {
                put(header, s);
            } else {
                put(current, s);
            }
        }
        List<SourceRecord> records = new ArrayList<>(loops.size());
        for (int i = 0; i < loops.size(); i++) {
            Map<String, String> fields = new LinkedHashMap<>(header);
            fields.putAll(loops.get(i));
            records.add(new SourceRecord(positions.get(i), fields));
        }
        return new Flattened(records, summary);
    }

    private static void put(Map<String, String> target, X12Segment s) {
        String prefix = s.id();
        int first = 1;
        if (QUALIFIED.contains(s.id()) && !s.element(1).isBlank()) {
            prefix = s.id() + "." + s.element(1).strip();
            first = 2;
        }
        String separator = prefix.equals(s.id()) ? "" : ".";
        for (int i = first; i <= s.size(); i++) {
            String value = s.element(i).strip();
            if (!value.isEmpty()) {
                target.putIfAbsent(prefix + separator + String.format("%02d", i), value);
            }
        }
    }
}
