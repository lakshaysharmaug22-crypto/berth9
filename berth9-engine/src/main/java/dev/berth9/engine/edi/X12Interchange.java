package dev.berth9.engine.edi;

import java.util.List;

/**
 * A parsed ISA..IEA interchange: the envelope of everything a trading partner sent in one file.
 *
 * @param elementSeparator   ISA character 4
 * @param componentSeparator ISA16
 * @param segmentTerminator  character that ends each segment
 * @param senderQualifier    ISA05
 * @param senderId           ISA06 (trimmed)
 * @param receiverQualifier  ISA07
 * @param receiverId         ISA08 (trimmed)
 * @param controlNumber      ISA13
 * @param usage              ISA15: P = production, T = test
 */
public record X12Interchange(char elementSeparator, char componentSeparator, char segmentTerminator,
                             String senderQualifier, String senderId, String receiverQualifier, String receiverId,
                             String controlNumber, String usage, List<X12Group> groups, List<X12Issue> issues) {

    public X12Interchange {
        groups = List.copyOf(groups);
        issues = List.copyOf(issues);
    }

    public List<X12Transaction> transactions() {
        return groups.stream().flatMap(g -> g.transactions().stream()).toList();
    }

    public boolean structurallyValid() {
        return issues.isEmpty()
                && groups.stream().allMatch(g -> g.issues().isEmpty())
                && transactions().stream().allMatch(X12Transaction::accepted);
    }
}
