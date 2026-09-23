package dev.berth9.engine.edi;

import java.util.List;

/**
 * A functional group (GS..GE) of transactions of the same kind.
 *
 * @param functionalId  GS01, e.g. {@code IN} for invoices, {@code PO} for purchase orders
 * @param senderCode    GS02
 * @param receiverCode  GS03
 * @param controlNumber GS06
 * @param version       GS08, e.g. {@code 004010}
 */
public record X12Group(String functionalId, String senderCode, String receiverCode, String controlNumber,
                       String version, List<X12Transaction> transactions, List<X12Issue> issues) {

    public X12Group {
        transactions = List.copyOf(transactions);
        issues = List.copyOf(issues);
    }
}
