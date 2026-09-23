package dev.berth9.engine.edi;

import java.util.List;

/**
 * One transaction set (ST..SE), e.g. an 810 invoice or an 850 purchase order.
 *
 * @param type          ST01, e.g. {@code 810}
 * @param controlNumber ST02
 * @param segments      every segment from ST to SE inclusive
 * @param issues        structural problems in this transaction set
 */
public record X12Transaction(String type, String controlNumber, List<X12Segment> segments, List<X12Issue> issues) {

    public X12Transaction {
        segments = List.copyOf(segments);
        issues = List.copyOf(issues);
    }

    public boolean accepted() {
        return issues.isEmpty();
    }
}
