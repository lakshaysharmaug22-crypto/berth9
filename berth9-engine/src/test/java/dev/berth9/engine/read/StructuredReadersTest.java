package dev.berth9.engine.read;

import dev.berth9.engine.format.FormatSniffer;
import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.model.SourceRecord;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredReadersTest {

    @Test
    void fixedWidthCarriesHeaderFieldsAndReadsTrailerCount() {
        String data = String.join("\n",
                "HINV-001  20260917",
                "D0001BOLT-M8   000100",
                "D0002NUT-M8    000250",
                "T000003");
        ReaderOptions options = new ReaderOptions(null, null, null, null, null, Map.of(
                "H", List.of(new FixedColumn("INV_NO", 2, 9), new FixedColumn("INV_DATE", 11, 8)),
                "D", List.of(new FixedColumn("LINE", 2, 4), new FixedColumn("PART", 6, 10), new FixedColumn("QTY", 16, 6)),
                "T", List.of(new FixedColumn("RECORD_COUNT", 2, 6))), "D", "T", null);
        List<SourceRecord> records = new ArrayList<>();
        try (FixedWidthRecordReader reader = new FixedWidthRecordReader(new BufferedReader(new StringReader(data)), options)) {
            reader.forEachRemaining(records::add);
            assertEquals("3", reader.controlTotals().get("lineCount"));
        }
        assertEquals(2, records.size());
        assertEquals("INV-001", records.get(1).get("H.INV_NO"));
        assertEquals("NUT-M8", records.get(1).get("PART"));
        assertEquals("000250", records.get(1).get("QTY"));
    }

    @Test
    void xmlRecordsCarryHeaderContextAndIgnoreDoctype() throws IOException {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE cXML SYSTEM "http://example.invalid/cXML.dtd">
                <cXML>
                  <InvoiceDetailRequestHeader invoiceID="LP-1" invoiceDate="2026-09-19T00:00:00-05:00"/>
                  <OrderReference orderID="PO-9"/>
                  <InvoiceDetailItem invoiceLineNumber="1" quantity="5">
                    <UnitPrice><Money currency="USD">1.08</Money></UnitPrice>
                    <ItemID><SupplierPartID>BOX-18</SupplierPartID></ItemID>
                  </InvoiceDetailItem>
                  <InvoiceDetailItem invoiceLineNumber="2" quantity="7">
                    <UnitPrice><Money currency="USD">2.35</Money></UnitPrice>
                    <ItemID><SupplierPartID>TAPE-48</SupplierPartID></ItemID>
                  </InvoiceDetailItem>
                </cXML>
                """;
        List<SourceRecord> records = new ArrayList<>();
        try (RecordReader reader = new XmlRecordReader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                ReaderOptions.defaults().withRecordElement("InvoiceDetailItem"))) {
            reader.forEachRemaining(records::add);
        }
        assertEquals(2, records.size());
        SourceRecord second = records.get(1);
        assertEquals("LP-1", second.get("InvoiceDetailRequestHeader/@invoiceID"));
        assertEquals("PO-9", second.get("OrderReference/@orderID"));
        assertEquals("7", second.get("@quantity"));
        assertEquals("2.35", second.get("UnitPrice/Money"));
        assertEquals("USD", second.get("UnitPrice/Money/@currency"));
        assertEquals("TAPE-48", second.get("ItemID/SupplierPartID"));
    }

    @Test
    void jsonRecordsComeFromTheNamedArrayWithContext() {
        String json = """
                {"invoice": {"number": "NC/0088", "currency": "USD"},
                 "lines": [{"lineNo": 1, "partNo": "PCB-3", "qty": 200}, {"lineNo": 2, "partNo": "HARN-12", "qty": 600}]}
                """;
        List<SourceRecord> records = new ArrayList<>();
        try (RecordReader reader = new JsonRecordReader(json, "lines")) {
            reader.forEachRemaining(records::add);
        }
        assertEquals(2, records.size());
        assertEquals("NC/0088", records.get(0).get("invoice/number"));
        assertEquals("HARN-12", records.get(1).get("partNo"));
        assertEquals("600", records.get(1).get("qty"));
    }

    @Test
    void sniffsFormatsFromContentNotExtension() {
        assertEquals(SourceFormat.X12, FormatSniffer.detect("invoice.txt", "ISA*00*".getBytes(StandardCharsets.UTF_8)));
        assertEquals(SourceFormat.XML, FormatSniffer.detect("feed.dat", "﻿<?xml version=\"1.0\"?>".getBytes(StandardCharsets.UTF_8)));
        assertEquals(SourceFormat.JSON, FormatSniffer.detect("push", "  {\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(SourceFormat.XLSX, FormatSniffer.detect("x", new byte[]{'P', 'K', 3, 4, 0}));
        assertEquals(SourceFormat.CSV, FormatSniffer.detect("lines.txt", "a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fixedWidthWithoutLayoutIsRejectedClearly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new FixedWidthRecordReader(new BufferedReader(new StringReader("x")), ReaderOptions.defaults()));
        assertTrue(e.getMessage().contains("layout"));
    }

    @Test
    void emptyCsvHasNoRecords() throws IOException {
        try (RecordReader reader = RecordReaders.withDefaults().open(SourceFormat.CSV,
                new ByteArrayInputStream(new byte[0]), ReaderOptions.defaults())) {
            assertFalse(reader.hasNext());
        }
    }
}
