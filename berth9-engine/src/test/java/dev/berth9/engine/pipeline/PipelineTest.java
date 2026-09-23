package dev.berth9.engine.pipeline;

import dev.berth9.engine.config.ConfigLoader;
import dev.berth9.engine.config.Json;
import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.map.MappingSpec;
import dev.berth9.engine.map.RecordMapper;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.read.RecordReader;
import dev.berth9.engine.read.RecordReaders;
import dev.berth9.engine.ref.InMemoryReferenceData;
import dev.berth9.engine.rules.Rule;
import dev.berth9.engine.rules.RuleEngine;
import dev.berth9.engine.rules.ValidationContext;
import dev.berth9.engine.transform.TransformContext;
import dev.berth9.engine.transform.TransformRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end over a real partner file: messy CSV in, validated canonical records out. */
class PipelineTest {

    private static final String SCHEMA = """
            {"name": "invoice-line", "businessKey": ["invoiceNumber", "lineNumber"], "fields": [
              {"name": "supplierId", "required": true},
              {"name": "invoiceNumber", "required": true},
              {"name": "invoiceDate", "type": "DATE", "required": true},
              {"name": "poNumber", "required": true},
              {"name": "lineNumber", "type": "INTEGER", "required": true},
              {"name": "sku", "required": true},
              {"name": "quantity", "type": "DECIMAL", "required": true},
              {"name": "unitPrice", "type": "DECIMAL", "required": true},
              {"name": "lineAmount", "type": "DECIMAL", "required": true},
              {"name": "currency", "required": true},
              {"name": "lineAmountUsd", "type": "DECIMAL", "derived": true}
            ]}
            """;

    private static final String SPEC = """
            {"partnerId": "NARMADA", "version": 1, "schema": "invoice-line", "format": "CSV",
             "fields": [
               {"target": "supplierId", "const": "NARMADA"},
               {"target": "invoiceNumber", "source": "Inv No", "transform": "trim | upper"},
               {"target": "invoiceDate", "source": "Inv Dt", "transform": "date(dd/MM/yyyy)"},
               {"target": "poNumber", "source": "PO Ref"},
               {"target": "lineNumber", "source": "Line", "transform": "number"},
               {"target": "sku", "source": "Item Code"},
               {"target": "quantity", "source": "Qty (Nos)", "transform": "number"},
               {"target": "unitPrice", "source": "Rate (Rs)", "transform": "amount"},
               {"target": "lineAmount", "source": "Amt (Rs)", "transform": "amount"},
               {"target": "currency", "const": "INR"}
             ],
             "enrich": [{"type": "convert", "amount": "lineAmount", "currency": "currency", "target": "lineAmountUsd", "base": "USD"}]}
            """;

    private static final String RULES = """
            {"rules": [
              {"id": "LINE_MATH", "type": "arithmetic", "field": "lineAmount", "left": "quantity", "op": "*", "right": "unitPrice", "tolerance": 0.05},
              {"id": "DUP", "type": "unique", "fields": ["invoiceNumber", "lineNumber"]},
              {"id": "PO_LINE", "type": "reference", "dataset": "po-line", "key": "{poNumber}|{sku}", "message": "SKU is not on this purchase order",
               "checks": [{"type": "withinPct", "field": "unitPrice", "refField": "unitPrice", "label": "PO price", "warnPct": 2, "errorPct": 5}]}
            ]}
            """;

    @Test
    void processesNarmadaFileEndToEnd() throws IOException {
        TargetSchema schema = ConfigLoader.schema(Json.parseObject(SCHEMA));
        MappingSpec spec = ConfigLoader.spec(Json.parseObject(SPEC));
        List<Rule> rules = ConfigLoader.rules(Json.parseObject(RULES));
        InMemoryReferenceData reference = new InMemoryReferenceData().put("fx", "INR", Map.of("rate", new BigDecimal("0.0114")));
        for (String[] line : new String[][]{{"NMW-FLG-SS304-2", "685.00"}, {"NMW-ELB-SS304-2", "312.00"}, {"NMW-TEE-SS304-2", "468.00"},
                {"NMW-FLG-SS304-3", "1140.00"}, {"NMW-PIPE-SS304-2-6M", "9850.00"}}) {
            reference.put("po-line", "HLIN-PO-2627-0144|" + line[0], Map.of("unitPrice", new BigDecimal(line[1])));
        }
        Pipeline pipeline = new Pipeline(new RecordMapper(spec, schema, TransformRegistry.standard(), TransformContext.empty()),
                rules, new RuleEngine());
        List<RecordOutcome> outcomes = new ArrayList<>();
        FileResult result;
        try (InputStream in = PipelineTest.class.getResourceAsStream("/narmada.csv");
             RecordReader reader = RecordReaders.withDefaults().open(SourceFormat.CSV, in, spec.reader())) {
            result = pipeline.run(reader, new ValidationContext(reference, LocalDate.of(2026, 9, 24)), outcomes::add);
        }
        assertEquals(7, result.total());
        assertEquals(5, result.valid());
        assertEquals(2, result.errors());

        RecordOutcome first = outcomes.get(0);
        assertEquals(LocalDate.of(2026, 9, 3), first.mapped().get("invoiceDate"));
        assertEquals(new BigDecimal("102750.00"), first.mapped().get("lineAmount"));
        assertEquals(new BigDecimal("1171.35"), first.mapped().get("lineAmountUsd"));
        assertEquals(List.of("Amt (Rs)"), first.mapped().lineage().get("lineAmount"));

        assertTrue(outcomes.get(5).violations().get(0).message().startsWith("duplicate of line 9"));
        assertTrue(outcomes.get(6).violations().get(0).message().contains("5.4% above PO price 685"));
    }

    @Test
    void controlTotalsThatDisagreeBecomeFileIssues() {
        assertEquals(1, Pipeline.controlChecks(Map.of("lineCount", "8"), 7).size());
        assertTrue(Pipeline.controlChecks(Map.of("lineCount", "7"), 7).isEmpty());
    }
}
