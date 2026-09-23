package dev.berth9.engine.rules;

import dev.berth9.engine.map.MappedRecord;
import dev.berth9.engine.model.Severity;
import dev.berth9.engine.model.Violation;
import dev.berth9.engine.ref.InMemoryReferenceData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
    private final RuleEngine engine = new RuleEngine();
    private final InMemoryReferenceData reference = new InMemoryReferenceData()
            .put("po-line", "PO-1|BOLT", Map.of("unitPrice", new BigDecimal("0.42"), "orderedQty", new BigDecimal("500"),
                    "invoicedQty", new BigDecimal("100")));

    private static MappedRecord record(long line, Map<String, Object> values) {
        return new MappedRecord(line, values, Map.of(), List.of());
    }

    private static Map<String, Object> invoiceLine(String price, String qty) {
        Map<String, Object> v = new HashMap<>();
        v.put("invoiceNumber", "INV-1");
        v.put("lineNumber", 1L);
        v.put("poNumber", "PO-1");
        v.put("sku", "BOLT");
        v.put("unitPrice", new BigDecimal(price));
        v.put("quantity", new BigDecimal(qty));
        v.put("lineAmount", new BigDecimal(price).multiply(new BigDecimal(qty)));
        v.put("invoiceDate", LocalDate.of(2026, 9, 20));
        return v;
    }

    private List<Violation> check(Rule rule, Map<String, Object> values) {
        return engine.evaluate(record(1, values), List.of(rule), new ValidationContext(reference, TODAY));
    }

    private static Rule poLine() {
        return new Rule.Reference("PO_LINE", "po-line", "{poNumber}|{sku}", true, "SKU is not on this purchase order", List.of(
                new RefCheck.WithinPct("unitPrice", "unitPrice", "PO price", new BigDecimal("2"), new BigDecimal("5")),
                new RefCheck.AtMost("quantity", "orderedQty", "invoicedQty", "open PO quantity", new BigDecimal("5"), Severity.ERROR)),
                Severity.ERROR);
    }

    @Test
    void priceVarianceIsWarningAboveTwoPercentAndErrorAboveFive() {
        assertTrue(check(poLine(), invoiceLine("0.42", "100")).isEmpty());
        List<Violation> warn = check(poLine(), invoiceLine("0.435", "100"));
        assertEquals(Severity.WARNING, warn.get(0).severity());
        assertTrue(warn.get(0).message().contains("above PO price 0.42"));
        assertEquals(Severity.ERROR, check(poLine(), invoiceLine("0.45", "100")).get(0).severity());
    }

    @Test
    void quantityCannotExceedOpenPoQuantityPlusTolerance() {
        assertTrue(check(poLine(), invoiceLine("0.42", "420")).isEmpty());
        List<Violation> over = check(poLine(), invoiceLine("0.42", "421"));
        assertEquals(1, over.size());
        assertTrue(over.get(0).message().contains("exceeds open PO quantity 400"));
    }

    @Test
    void missingReferenceUsesTheRuleMessage() {
        Map<String, Object> v = invoiceLine("0.42", "10");
        v.put("sku", "WASHER");
        assertEquals("SKU is not on this purchase order (PO-1|WASHER)", check(poLine(), v).get(0).message());
    }

    @Test
    void mustNotExistCatchesLinesAlreadyInvoiced() {
        reference.put("invoiced-lines", "KESTREL|INV-1|1", Map.of());
        Map<String, Object> v = invoiceLine("0.42", "10");
        v.put("supplierId", "KESTREL");
        Rule rule = new Rule.Reference("ALREADY", "invoiced-lines", "{supplierId}|{invoiceNumber}|{lineNumber}", false,
                "already received", List.of(), Severity.ERROR);
        assertEquals(1, check(rule, v).size());
    }

    @Test
    void lineMathAllowsRoundingButNotTypos() {
        Rule math = new Rule.Arithmetic("MATH", "lineAmount", "quantity", "*", "unitPrice", new BigDecimal("0.05"), new BigDecimal("0.5"), Severity.ERROR);
        Map<String, Object> v = invoiceLine("3420", "60");
        v.put("lineAmount", new BigDecimal("205200.00"));
        assertTrue(check(math, v).isEmpty());
        v.put("lineAmount", new BigDecimal("202500.00"));
        assertEquals("lineAmount 202500 does not equal quantity * unitPrice = 205200", check(math, v).get(0).message());
    }

    @Test
    void duplicatesWithinAFileReferToTheFirstLine() {
        Rule unique = new Rule.UniqueInFile("DUP", List.of("invoiceNumber", "lineNumber"), Severity.ERROR);
        ValidationContext ctx = new ValidationContext(reference, TODAY);
        assertTrue(engine.evaluate(record(9, invoiceLine("1", "1")), List.of(unique), ctx).isEmpty());
        List<Violation> dup = engine.evaluate(record(10, invoiceLine("1", "1")), List.of(unique), ctx);
        assertEquals("duplicate of line 9 (invoiceNumber + lineNumber)", dup.get(0).message());
    }

    @Test
    void gstinChecksumAndConditionalRules() {
        assertTrue(Gstin.isValid("27AAPFU0939F1ZV"));
        assertFalse(Gstin.isValid("27AAPFU0939F1ZX"));
        Rule gst = new Rule.When("GST", "currency", List.of("INR"), List.of(new Rule.Gstin("GSTIN", "gstin", Severity.ERROR)), Severity.ERROR);
        Map<String, Object> v = invoiceLine("1", "1");
        v.put("gstin", "27AAPFU0939F1ZX");
        v.put("currency", "USD");
        assertTrue(check(gst, v).isEmpty(), "GST rules only apply to INR invoices");
        v.put("currency", "INR");
        assertTrue(check(gst, v).get(0).message().contains("check digit does not match"));
    }

    @Test
    void futureInvoiceDatesAreRejected() {
        Rule window = new Rule.DateWindow("DATE", "invoiceDate", null, 1, Severity.ERROR);
        Map<String, Object> v = invoiceLine("1", "1");
        v.put("invoiceDate", TODAY.plusDays(5));
        assertEquals(1, check(window, v).size());
    }
}
