package dev.berth9.engine.edi;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a 997 Functional Acknowledgment for a received interchange.
 *
 * <p>A 997 answers "did your file arrive and is it structurally valid X12?", one AK2/AK5 pair per
 * transaction set and an AK9 per functional group. It deliberately says nothing about business
 * validity (a price that doesn't match the PO): that is an application-level answer, which in X12
 * belongs in an 824 Application Advice, not in the 997.
 */
public final class Ack997 {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter CCYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

    private Ack997() {
    }

    /**
     * @param received           the parsed interchange being acknowledged
     * @param interchangeControl control number for the outbound ISA13/IEA02
     * @param groupControl       control number for the outbound GS06/GE02
     * @param now                timestamp written into the envelopes
     */
    public static String build(X12Interchange received, long interchangeControl, long groupControl, LocalDateTime now) {
        char e = received.elementSeparator();
        char c = received.componentSeparator();
        String isaControl = String.format("%09d", interchangeControl);
        String gsControl = String.valueOf(groupControl);
        String ourCode = received.groups().isEmpty() ? received.receiverId() : received.groups().get(0).receiverCode();
        String theirCode = received.groups().isEmpty() ? received.senderId() : received.groups().get(0).senderCode();

        List<String> segments = new ArrayList<>();
        segments.add(join(e, "ISA", "00", pad("", 10), "00", pad("", 10),
                received.receiverQualifier(), pad(received.receiverId(), 15),
                received.senderQualifier(), pad(received.senderId(), 15),
                now.format(YYMMDD), now.format(HHMM), "U", "00401", isaControl, "0",
                received.usage().isBlank() ? "P" : received.usage(), String.valueOf(c)));
        segments.add(join(e, "GS", "FA", ourCode, theirCode, now.format(CCYYMMDD), now.format(HHMM), gsControl, "X", "004010"));

        int setNumber = 1;
        for (X12Group group : received.groups()) {
            String stControl = String.format("%04d", setNumber++);
            List<String> body = new ArrayList<>();
            body.add(join(e, "ST", "997", stControl));
            body.add(join(e, "AK1", group.functionalId(), group.controlNumber()));
            int accepted = 0;
            for (X12Transaction tx : group.transactions()) {
                body.add(join(e, "AK2", tx.type(), tx.controlNumber()));
                if (tx.accepted()) {
                    body.add(join(e, "AK5", "A"));
                    accepted++;
                } else {
                    String code = tx.issues().get(0).code();
                    body.add(join(e, "AK5", "R", code == null || code.isBlank() ? "5" : code));
                }
            }
            int count = group.transactions().size();
            body.add(join(e, "AK9", groupStatus(group, accepted, count),
                    String.valueOf(count), String.valueOf(count), String.valueOf(accepted)));
            body.add(join(e, "SE", String.valueOf(body.size() + 1), stControl));
            segments.addAll(body);
        }
        segments.add(join(e, "GE", String.valueOf(received.groups().size()), gsControl));
        segments.add(join(e, "IEA", "1", isaControl));

        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            out.append(segment).append(received.segmentTerminator()).append('\n');
        }
        return out.toString();
    }

    /** AK901: A accepted, E accepted with errors noted, P partially accepted, R rejected. */
    static String groupStatus(X12Group group, int accepted, int count) {
        if (count == 0 || accepted == 0) {
            return "R";
        }
        if (accepted < count) {
            return "P";
        }
        return group.issues().isEmpty() ? "A" : "E";
    }

    private static String join(char separator, String... elements) {
        return String.join(String.valueOf(separator), elements);
    }

    private static String pad(String value, int width) {
        String v = value == null ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }
}
