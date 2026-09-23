package dev.berth9.engine.edi;

import dev.berth9.engine.format.FormatSniffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses an ANSI X12 interchange and validates its envelopes the way a trading-partner gateway must:
 * ISA13/IEA02 and GS06/GE02 control numbers, SE01 segment counts, ST02/SE02 pairing, and declared
 * group/transaction counts. Structural problems are collected (not thrown) so they can be reported
 * back to the partner in a 997 functional acknowledgment.
 *
 * <p>Delimiters are read from the ISA segment itself: the element separator is character 4, the
 * component separator is ISA16 and the segment terminator is the character right after it.
 */
public final class X12Parser {

    private final Set<String> supportedTypes;

    public X12Parser() {
        this(Set.of("810", "850"));
    }

    public X12Parser(Set<String> supportedTypes) {
        this.supportedTypes = Set.copyOf(supportedTypes);
    }

    public X12Interchange parse(String raw) {
        String text = FormatSniffer.stripBom(raw == null ? "" : raw).stripLeading();
        if (!text.startsWith("ISA") || text.length() < 16) {
            throw new X12Exception("not an X12 interchange: file does not start with an ISA segment");
        }
        char elementSeparator = text.charAt(3);
        int separators = 0;
        int isa16 = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == elementSeparator && ++separators == 16) {
                isa16 = i + 1;
                break;
            }
        }
        if (isa16 < 0 || isa16 + 1 >= text.length()) {
            throw new X12Exception("truncated ISA segment: expected 16 elements");
        }
        char componentSeparator = text.charAt(isa16);
        char terminator = text.charAt(isa16 + 1);
        return walk(split(text, elementSeparator, terminator), elementSeparator, componentSeparator, terminator);
    }

    private static List<X12Segment> split(String text, char elementSeparator, char terminator) {
        List<X12Segment> segments = new ArrayList<>();
        int position = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == terminator) {
                String raw = text.substring(start, i).strip();
                start = i + 1;
                if (raw.isEmpty()) {
                    continue;
                }
                List<String> parts = splitElements(raw, elementSeparator);
                segments.add(new X12Segment(++position, parts.get(0).strip(), parts.subList(1, parts.size())));
            }
        }
        return segments;
    }

    private static List<String> splitElements(String segment, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < segment.length(); i++) {
            if (segment.charAt(i) == separator) {
                parts.add(segment.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(segment.substring(start));
        return parts;
    }

    private X12Interchange walk(List<X12Segment> segments, char elementSeparator, char componentSeparator, char terminator) {
        List<X12Issue> interchangeIssues = new ArrayList<>();
        X12Segment isa = segments.get(0);
        if (isa.size() != 16) {
            interchangeIssues.add(issue(X12Issue.Level.INTERCHANGE, "", "ISA has " + isa.size() + " elements, expected 16", isa.position()));
        }
        List<X12Group> groups = new ArrayList<>();
        GroupState group = null;
        TxState tx = null;
        boolean ieaSeen = false;

        for (int i = 1; i < segments.size(); i++) {
            X12Segment s = segments.get(i);
            switch (s.id()) {
                case "GS" -> {
                    if (tx != null) {
                        group.transactions.add(tx.closeWithoutTrailer(s.position()));
                        tx = null;
                    }
                    if (group != null) {
                        group.issues.add(issue(X12Issue.Level.GROUP, "", "functional group " + group.controlNumber() + " has no GE trailer", s.position()));
                        groups.add(group.build());
                    }
                    group = new GroupState(s);
                }
                case "ST" -> {
                    if (group == null) {
                        group = new GroupState(null);
                        group.issues.add(issue(X12Issue.Level.GROUP, "", "transaction set found without a GS header", s.position()));
                    }
                    if (tx != null) {
                        group.transactions.add(tx.closeWithoutTrailer(s.position()));
                    }
                    tx = new TxState(s);
                    if (!supportedTypes.contains(s.element(1))) {
                        tx.issues.add(issue(X12Issue.Level.TRANSACTION, "1", "transaction set " + s.element(1) + " is not supported", s.position()));
                    }
                }
                case "SE" -> {
                    if (tx == null) {
                        interchangeIssues.add(issue(X12Issue.Level.INTERCHANGE, "", "SE found without a matching ST", s.position()));
                        continue;
                    }
                    tx.segments.add(s);
                    if (!s.element(2).strip().equals(tx.st.element(2).strip())) {
                        tx.issues.add(issue(X12Issue.Level.TRANSACTION, "3",
                                "SE02 " + s.element(2) + " does not match ST02 " + tx.st.element(2), s.position()));
                    }
                    int declared = parseInt(s.element(1));
                    if (declared != tx.segments.size()) {
                        tx.issues.add(issue(X12Issue.Level.TRANSACTION, "4",
                                "SE01 declares " + s.element(1) + " segments but " + tx.segments.size() + " were received", s.position()));
                    }
                    group.transactions.add(tx.build());
                    tx = null;
                }
                case "GE" -> {
                    if (tx != null) {
                        group.transactions.add(tx.closeWithoutTrailer(s.position()));
                        tx = null;
                    }
                    if (group == null) {
                        interchangeIssues.add(issue(X12Issue.Level.INTERCHANGE, "", "GE found without a matching GS", s.position()));
                        continue;
                    }
                    if (!s.element(2).strip().equals(group.controlNumber())) {
                        group.issues.add(issue(X12Issue.Level.GROUP, "",
                                "GE02 " + s.element(2) + " does not match GS06 " + group.controlNumber(), s.position()));
                    }
                    if (parseInt(s.element(1)) != group.transactions.size()) {
                        group.issues.add(issue(X12Issue.Level.GROUP, "",
                                "GE01 declares " + s.element(1) + " transaction sets but " + group.transactions.size() + " were received", s.position()));
                    }
                    groups.add(group.build());
                    group = null;
                }
                case "IEA" -> {
                    ieaSeen = true;
                    if (tx != null) {
                        group.transactions.add(tx.closeWithoutTrailer(s.position()));
                        tx = null;
                    }
                    if (group != null) {
                        group.issues.add(issue(X12Issue.Level.GROUP, "", "functional group " + group.controlNumber() + " has no GE trailer", s.position()));
                        groups.add(group.build());
                        group = null;
                    }
                    if (!s.element(2).strip().equals(isa.element(13).strip())) {
                        interchangeIssues.add(issue(X12Issue.Level.INTERCHANGE, "",
                                "IEA02 " + s.element(2) + " does not match ISA13 " + isa.element(13), s.position()));
                    }
                    if (parseInt(s.element(1)) != groups.size()) {
                        interchangeIssues.add(issue(X12Issue.Level.INTERCHANGE, "",
                                "IEA01 declares " + s.element(1) + " functional groups but " + groups.size() + " were received", s.position()));
                    }
                }
                default -> {
                    if (tx != null) {
                        tx.segments.add(s);
                    } else {
                        interchangeIssues.add(issue(X12Issue.Level.INTERCHANGE, "", "segment " + s.id() + " is outside any transaction set", s.position()));
                    }
                }
            }
        }
        int end = segments.get(segments.size() - 1).position();
        if (tx != null) {
            if (group == null) {
                group = new GroupState(null);
            }
            group.transactions.add(tx.closeWithoutTrailer(end));
        }
        if (group != null) {
            group.issues.add(issue(X12Issue.Level.GROUP, "", "functional group " + group.controlNumber() + " has no GE trailer", end));
            groups.add(group.build());
        }
        if (!ieaSeen) {
            interchangeIssues.add(issue(X12Issue.Level.INTERCHANGE, "", "interchange has no IEA trailer", end));
        }
        return new X12Interchange(elementSeparator, componentSeparator, terminator,
                isa.element(5).strip(), isa.element(6).strip(), isa.element(7).strip(), isa.element(8).strip(),
                isa.element(13).strip(), isa.element(15).strip(), groups, interchangeIssues);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static X12Issue issue(X12Issue.Level level, String code, String message, int position) {
        return new X12Issue(level, code, message, position);
    }

    private static final class GroupState {
        final X12Segment gs;
        final List<X12Transaction> transactions = new ArrayList<>();
        final List<X12Issue> issues = new ArrayList<>();

        GroupState(X12Segment gs) {
            this.gs = gs;
        }

        String controlNumber() {
            return gs == null ? "" : gs.element(6).strip();
        }

        X12Group build() {
            if (gs == null) {
                return new X12Group("", "", "", "", "", transactions, issues);
            }
            return new X12Group(gs.element(1).strip(), gs.element(2).strip(), gs.element(3).strip(),
                    gs.element(6).strip(), gs.element(8).strip(), transactions, issues);
        }
    }

    private static final class TxState {
        final X12Segment st;
        final List<X12Segment> segments = new ArrayList<>();
        final List<X12Issue> issues = new ArrayList<>();

        TxState(X12Segment st) {
            this.st = st;
            segments.add(st);
        }

        X12Transaction closeWithoutTrailer(int position) {
            issues.add(new X12Issue(X12Issue.Level.TRANSACTION, "2",
                    "transaction set " + st.element(2) + " has no SE trailer", position));
            return build();
        }

        X12Transaction build() {
            return new X12Transaction(st.element(1).strip(), st.element(2).strip(), segments, issues);
        }
    }
}
