package dev.berth9.engine.suggest;

import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.model.FieldType;
import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.model.TargetField;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.model.ValueKind;
import dev.berth9.engine.read.ReaderOptions;
import dev.berth9.engine.read.RecordReader;
import dev.berth9.engine.read.RecordReaders;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingSuggesterTest {

    private static final TargetSchema SCHEMA = new TargetSchema("invoice-line", "Invoice line", List.of(
            new TargetField("invoiceNumber", FieldType.STRING, true, "Invoice number", List.of("invoice no", "bill no"), Set.of(ValueKind.CODE), false),
            new TargetField("invoiceDate", FieldType.DATE, true, "Invoice date", List.of("invoice date"), Set.of(ValueKind.DATE), false),
            new TargetField("quantity", FieldType.DECIMAL, true, "Quantity", List.of("qty"), Set.of(ValueKind.INTEGER, ValueKind.DECIMAL), false),
            new TargetField("unitPrice", FieldType.DECIMAL, true, "Unit price", List.of("rate", "price each"), Set.of(ValueKind.AMOUNT, ValueKind.DECIMAL), false),
            new TargetField("taxAmount", FieldType.DECIMAL, false, "Tax", List.of("tax", "cgst", "sgst", "igst"), Set.of(ValueKind.AMOUNT, ValueKind.DECIMAL), false),
            new TargetField("gstin", FieldType.STRING, false, "GSTIN", List.of("gstin"), Set.of(ValueKind.GSTIN), false),
            new TargetField("amountUsd", FieldType.DECIMAL, false, "Amount USD", List.of(), Set.of(), true)),
            List.of("invoiceNumber"));

    private static SuggestionReport suggest(String csv, String locale) throws IOException {
        try (RecordReader reader = RecordReaders.withDefaults().open(SourceFormat.CSV,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), ReaderOptions.defaults())) {
            List<SourceRecord> sample = new ArrayList<>();
            reader.forEachRemaining(sample::add);
            return new MappingSuggester().suggest(SCHEMA, reader.columns(), sample, locale);
        }
    }

    private static Suggestion field(SuggestionReport report, String name) {
        return report.suggestions().stream().filter(s -> s.field().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void expandsAbbreviatedHeaders() throws IOException {
        SuggestionReport report = suggest("Inv No.,Inv Dt,Qty (Nos),Rate (Rs)\nNMW/0412,15/09/2026,150,685.00\n", "IN");
        assertEquals(List.of("Inv No"), field(report, "invoiceNumber").sources());
        assertEquals(List.of("Inv Dt"), field(report, "invoiceDate").sources());
        assertEquals(List.of("Qty (Nos)"), field(report, "quantity").sources());
        assertEquals(List.of("Rate (Rs)"), field(report, "unitPrice").sources());
        assertEquals("HIGH", field(report, "unitPrice").band());
        assertEquals("date(dd/MM/yyyy)", field(report, "invoiceDate").transform());
    }

    @Test
    void settlesAmbiguousDatesFromPartnerLocale() throws IOException {
        String csv = "Invoice #,Invoice Date\nCC-1,09/04/2026\nCC-2,09/11/2026\n";
        assertEquals("date(MM/dd/yyyy)", field(suggest(csv, "US"), "invoiceDate").transform());
        assertEquals("date(dd/MM/yyyy)", field(suggest(csv, "IN"), "invoiceDate").transform());
        assertTrue(field(suggest(csv, "US"), "invoiceDate").reasons().stream().anyMatch(r -> r.contains("ambiguous")));
    }

    @Test
    void sumsSiblingTaxColumnsFromTheSameHeaderGroup() throws IOException {
        String csv = "Bill No,GST (Rs) CGST,GST (Rs) SGST,GST (Rs) IGST\nSP-1,\"30,780.00\",\"30,780.00\",\nSP-2,,,\"28,512.00\"\n";
        Suggestion tax = field(suggest(csv, "IN"), "taxAmount");
        assertEquals(3, tax.sources().size());
        assertEquals("+", tax.join());
    }

    @Test
    void recognisesGstinByValueEvenWithAMeaninglessHeader() throws IOException {
        SuggestionReport report = suggest("col7,Bill No\n27AAPFU0939F1ZV,A-1\n27AAPFU0939F1ZV,A-2\n", "IN");
        assertEquals(List.of("col7"), field(report, "gstin").sources());
    }

    @Test
    void derivedFieldsAreNeverSuggestedAndRequiredGapsAreListed() throws IOException {
        SuggestionReport report = suggest("Bill No,Qty\nA-1,5\n", "US");
        assertTrue(report.suggestions().stream().noneMatch(s -> s.field().equals("amountUsd")));
        assertTrue(report.missingRequired().contains("unitPrice"));
    }
}
