package dev.berth9.engine.read;

import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.model.SourceRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CsvReaderTest {

    private static List<SourceRecord> read(String csv) throws IOException {
        try (RecordReader reader = RecordReaders.withDefaults().open(SourceFormat.CSV,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), ReaderOptions.defaults())) {
            List<SourceRecord> out = new ArrayList<>();
            reader.forEachRemaining(out::add);
            return out;
        }
    }

    @Test
    void skipsBannerRowsBlankRowsAndTotals() throws IOException {
        String csv = """
                Acme Supplier - Invoice Lines
                "Supplier GSTIN: 24AAFFN8823K1Z0",,,
                Inv No.,Inv Dt,Qty,Amount
                INV-1,03/09/2026,10,"1,250.00"
                ,,,
                INV-2,04/09/2026,5,625.00
                Total,,,"1,875.00"
                """;
        List<SourceRecord> records = read(csv);
        assertEquals(2, records.size());
        assertEquals("INV-1", records.get(0).get("Inv No"));
        assertEquals("1,250.00", records.get(0).get("Amount"));
        assertEquals(4, records.get(0).line());
    }

    @Test
    void handlesQuotedDelimitersEscapedQuotesAndEmbeddedNewlines() throws IOException {
        String csv = "sku,description,qty\r\n"
                + "F-2,\"Flange, SS304, 2\"\"\",5\r\n"
                + "P-6,\"Pipe\nsecond line\",7\r\n";
        List<SourceRecord> records = read(csv);
        assertEquals(2, records.size());
        assertEquals("Flange, SS304, 2\"", records.get(0).get("description"));
        assertEquals("Pipe\nsecond line", records.get(1).get("description"));
        assertEquals("7", records.get(1).get("qty"));
    }

    @Test
    void sniffsPipeDelimiterAndStripsBom() throws IOException {
        String csv = "﻿invoice|line|sku\nA-1|1|X-9\nA-1|2|X-10\n";
        List<SourceRecord> records = read(csv);
        assertEquals(2, records.size());
        assertEquals("X-10", records.get(1).get("sku"));
        assertEquals("A-1", records.get(0).get("invoice"));
    }

    @Test
    void mergesTwoRowGroupedHeaders() throws IOException {
        String csv = """
                Sr No,Invoice,,Tax (INR),,
                ,No.,Date,CGST,SGST,IGST
                1,SP/0913,15-09-2026,"30,780.00","30,780.00",
                """;
        try (RecordReader reader = RecordReaders.withDefaults().open(SourceFormat.CSV,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), ReaderOptions.defaults())) {
            assertEquals(List.of("Sr No", "Invoice No", "Invoice Date", "Tax (INR) CGST", "Tax (INR) SGST", "Tax (INR) IGST"),
                    reader.columns());
            SourceRecord first = reader.next();
            assertEquals("SP/0913", first.get("Invoice No"));
            assertEquals("30,780.00", first.get("Tax (INR) SGST"));
            assertFalse(reader.hasNext());
        }
    }

    @Test
    void dropsHeaderRepeatedAtPageBreak() throws IOException {
        String csv = "sku,qty,price\nA-1,1,2.00\nsku,qty,price\nB-2,3,4.00\n";
        List<SourceRecord> records = read(csv);
        assertEquals(2, records.size());
        assertEquals("B-2", records.get(1).get("sku"));
    }

    static InputStream resource(String name) {
        return CsvReaderTest.class.getResourceAsStream("/" + name);
    }
}
