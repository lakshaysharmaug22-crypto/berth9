package dev.berth9.engine.suggest;

import java.util.Map;

/**
 * Plain-English meaning of the X12 element keys produced by the flattener, so the suggester can match
 * {@code IT104} to "unit price" the same way it matches a spreadsheet header.
 */
public final class X12Dictionary {

    private static final Map<String, String> MEANINGS = Map.ofEntries(
            Map.entry("BIG01", "invoice date"),
            Map.entry("BIG02", "invoice number"),
            Map.entry("BIG03", "purchase order date"),
            Map.entry("BIG04", "purchase order number"),
            Map.entry("BEG03", "purchase order number"),
            Map.entry("BEG05", "purchase order date"),
            Map.entry("IT101", "line number"),
            Map.entry("IT102", "quantity invoiced"),
            Map.entry("IT103", "unit of measure"),
            Map.entry("IT104", "unit price"),
            Map.entry("IT1.VP", "vendor part number sku"),
            Map.entry("IT1.BP", "buyer part number"),
            Map.entry("IT1.UP", "upc"),
            Map.entry("PO101", "line number"),
            Map.entry("PO102", "quantity ordered"),
            Map.entry("PO103", "unit of measure"),
            Map.entry("PO104", "unit price"),
            Map.entry("PO1.VP", "vendor part number sku"),
            Map.entry("PO1.BP", "buyer part number"),
            Map.entry("PID05", "item description"),
            Map.entry("CUR.SE.02", "currency"),
            Map.entry("CUR.BY.02", "currency"),
            Map.entry("REF.PO.02", "purchase order number"),
            Map.entry("REF.IA.02", "vendor number"),
            Map.entry("N1.ST.02", "ship to name"),
            Map.entry("N1.ST.04", "ship to code"),
            Map.entry("N1.RE.02", "remit to name"),
            Map.entry("N1.BY.02", "buyer name"),
            Map.entry("N1.SE.02", "seller supplier name"),
            Map.entry("DTM.002.02", "requested delivery date"),
            Map.entry("DTM.011.02", "ship date"),
            Map.entry("TXI.ST.02", "sales tax amount"),
            Map.entry("TXI.GS.02", "gst tax amount"),
            Map.entry("ISA06", "sender id"),
            Map.entry("GS02", "sender code"));

    private X12Dictionary() {
    }

    public static String describe(String key) {
        return MEANINGS.get(key);
    }
}
