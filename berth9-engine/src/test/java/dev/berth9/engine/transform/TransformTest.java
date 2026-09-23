package dev.berth9.engine.transform;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformTest {

    private final TransformRegistry registry = TransformRegistry.standard();
    private final TransformContext context = new TransformContext(Map.of("uom", Map.of("PCS", "EA", "NOS", "EA")));

    private Object run(String expression, Object value) {
        return TransformPipeline.compile(expression, registry).apply(value, context);
    }

    @Test
    void parsesUsIndianAndEuropeanAmounts() {
        assertEquals(new BigDecimal("1234.50"), Amounts.parse("$1,234.50"));
        assertEquals(new BigDecimal("123456.00"), Amounts.parse("₹1,23,456.00"));
        assertEquals(new BigDecimal("4500"), Amounts.parse("Rs. 4,500/-"));
        assertEquals(0, new BigDecimal("1250000").compareTo(Amounts.parse("12.5 L")));
        assertEquals(0, new BigDecimal("12000000").compareTo(Amounts.parse("1.2 Cr")));
        assertEquals(new BigDecimal("-1200.00"), Amounts.parse("(1,200.00)"));
        assertEquals(new BigDecimal("-1200.00"), Amounts.parse("1,200.00 CR"));
        assertEquals(new BigDecimal("1234.56"), Amounts.parse("1.234,56"));
        assertEquals(new BigDecimal("210.00"), Amounts.parse("210.00 USD"));
        assertThrows(TransformException.class, () -> Amounts.parse("12,3x"));
    }

    @Test
    void datesAreStrictAndUnderstandExcelAndIso() {
        assertEquals(LocalDate.of(2026, 9, 3), Dates.parse("03/09/2026", List.of("dd/MM/yyyy")));
        assertEquals(LocalDate.of(2026, 3, 9), Dates.parse("03/09/2026", List.of("MM/dd/yyyy")));
        assertEquals(LocalDate.of(2026, 9, 19), Dates.parse("2026-09-19T00:00:00-05:00", List.of()));
        assertEquals(LocalDate.of(2026, 9, 21), Dates.parse("46286", List.of()));
        assertEquals(LocalDate.of(2026, 9, 15), Dates.parse("15-SEP-26", List.of("dd-MMM-yy")));
        assertThrows(TransformException.class, () -> Dates.parse("31/02/2026", List.of("dd/MM/yyyy")));
    }

    @Test
    void infersBothOrdersWhenEveryDateIsAmbiguous() {
        List<String> ambiguous = Dates.inferPatterns(List.of("03/09/2026", "11/09/2026"));
        assertTrue(ambiguous.contains("dd/MM/yyyy") && ambiguous.contains("MM/dd/yyyy"));
        List<String> settled = Dates.inferPatterns(List.of("03/09/2026", "15/09/2026"));
        assertTrue(settled.contains("dd/MM/yyyy"));
        assertTrue(!settled.contains("MM/dd/yyyy"));
    }

    @Test
    void pipelinesChainAndSupportQuotedArguments() {
        assertEquals("EA", run("trim | upper | lookup(uom)", " pcs "));
        assertEquals("BX", run("upper | lookup(uom, *)", "bx"));
        assertEquals("12345", run("replace('[^0-9]', '')", "12-34|5"));
        assertEquals(new BigDecimal("2.1800"), run("implied(4)", "00000021800"));
        assertEquals("Hex Bolt M8X40 Zinc", run("collapse | title", "HEX  BOLT M8X40 ZINC"));
        assertEquals("N/A", run("default(N/A)", null));
        assertNull(run("trim", null));
    }

    @Test
    void unknownTransformFailsAtCompileTime() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TransformPipeline.compile("trim | amonut", registry));
        assertTrue(e.getMessage().contains("amonut"));
    }

    @Test
    void unknownLookupValueWithoutFallbackIsAnError() {
        assertThrows(TransformException.class, () -> run("lookup(uom)", "barrel"));
    }
}
