package dev.berth9.engine.edi;

import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.read.ReaderOptions;
import dev.berth9.engine.read.X12RecordReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class X12Test {

    private static String resource(String name) throws IOException {
        try (InputStream in = X12Test.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesEnvelopesAndDelimitersFromIsa() throws IOException {
        X12Interchange ic = new X12Parser().parse(resource("kestrel-810.x12"));
        assertEquals('*', ic.elementSeparator());
        assertEquals('~', ic.segmentTerminator());
        assertEquals('>', ic.componentSeparator());
        assertEquals("KESTRELFAST", ic.senderId());
        assertEquals("000004127", ic.controlNumber());
        assertEquals(1, ic.groups().size());
        assertEquals(2, ic.transactions().size());
        assertTrue(ic.structurallyValid());
    }

    @Test
    void flattensOneRecordPerIt1LoopWithHeaderContext() throws IOException {
        List<SourceRecord> records = new ArrayList<>();
        try (X12RecordReader reader = new X12RecordReader(resource("kestrel-810.x12"), ReaderOptions.defaults())) {
            reader.forEachRemaining(records::add);
            assertEquals("8", reader.controlTotals().get("lineCount"));
        }
        assertEquals(8, records.size());
        SourceRecord fourth = records.get(3);
        assertEquals("KF-INV-88412", fourth.get("BIG02"));
        assertEquals("PO-2026-00412", fourth.get("BIG04"));
        assertEquals("0.125", fourth.get("IT104"));
        assertEquals("KF-NUT-M10", fourth.get("IT1.VP"));
        assertEquals("HEX NUT M10 ZINC", fourth.get("PID05"));
        assertEquals("USD", fourth.get("CUR.SE.02"));
        assertEquals("KF-INV-88419", records.get(7).get("BIG02"));
    }

    @Test
    void segmentCountMismatchIsReportedWithAk5Code4() throws IOException {
        X12Interchange ic = new X12Parser().parse(resource("kestrel-810-broken.x12"));
        assertFalse(ic.structurallyValid());
        X12Transaction tx = ic.transactions().get(0);
        assertEquals("4", tx.issues().get(0).code());
        String ack = Ack997.build(ic, 77, 12, LocalDateTime.of(2026, 9, 23, 9, 30));
        assertTrue(ack.contains("AK5*R*4~"));
        assertTrue(ack.contains("AK9*R*1*1*0~"));
    }

    @Test
    void acknowledgesAcceptedInterchangeWithSwappedParties() throws IOException {
        X12Interchange ic = new X12Parser().parse(resource("kestrel-810.x12"));
        String ack = Ack997.build(ic, 77, 12, LocalDateTime.of(2026, 9, 23, 9, 30));
        String[] segments = ack.split("~\n");
        assertEquals(106, segments[0].length() + 1, "ISA must be fixed-width (106 chars incl. terminator)");
        assertTrue(segments[0].contains("*ZZ*HARBORLINE     *ZZ*KESTRELFAST    *"));
        assertTrue(ack.contains("GS*FA*HARBORLINE*KESTRELFAST*20260923*0930*12*X*004010~"));
        assertTrue(ack.contains("AK1*IN*4127~"));
        assertTrue(ack.contains("AK9*A*2*2*2~"));
        assertTrue(ack.contains("SE*8*0001~"));
        assertTrue(ack.contains("IEA*1*000000077~"));
    }

    @Test
    void missingTrailersAreIssuesNotCrashes() {
        String truncated = "ISA*00*          *00*          *ZZ*A              *ZZ*B              *260101*1200*U*00401*000000001*0*P*>~"
                + "GS*IN*A*B*20260101*1200*1*X*004010~ST*810*0001~BIG*20260101*I-1**P-1~IT1*1*1*EA*1**VP*X~";
        X12Interchange ic = new X12Parser().parse(truncated);
        assertEquals("2", ic.transactions().get(0).issues().get(0).code());
        assertFalse(ic.issues().isEmpty());
    }

    @Test
    void rejectsNonX12Input() {
        assertThrows(X12Exception.class, () -> new X12Parser().parse("hello"));
    }
}
