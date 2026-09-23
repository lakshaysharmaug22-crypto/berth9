package dev.berth9.engine.edi;

import java.util.List;

/**
 * One X12 segment, e.g. {@code IT1*1*500*EA*0.42**VP*KF-HEX-M8-40}.
 *
 * @param position 1-based position of the segment within the interchange
 * @param id       segment id ({@code IT1})
 * @param elements element values after the id, in order ({@code element(1)} is IT101)
 */
public record X12Segment(int position, String id, List<String> elements) {

    public X12Segment {
        elements = List.copyOf(elements);
    }

    /** 1-based element accessor; returns "" when the element is absent. */
    public String element(int n) {
        return n >= 1 && n <= elements.size() ? elements.get(n - 1) : "";
    }

    public int size() {
        return elements.size();
    }
}
