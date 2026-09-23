package dev.berth9.engine.cli;

import dev.berth9.engine.config.ConfigLoader;
import dev.berth9.engine.config.Json;
import dev.berth9.engine.edi.Ack997;
import dev.berth9.engine.edi.X12Interchange;
import dev.berth9.engine.edi.X12Parser;
import dev.berth9.engine.format.FormatSniffer;
import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.map.MappingSpec;
import dev.berth9.engine.map.RecordMapper;
import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.model.Violation;
import dev.berth9.engine.pipeline.FileResult;
import dev.berth9.engine.pipeline.Pipeline;
import dev.berth9.engine.pipeline.RecordOutcome;
import dev.berth9.engine.read.ReaderOptions;
import dev.berth9.engine.read.RecordReader;
import dev.berth9.engine.read.RecordReaders;
import dev.berth9.engine.ref.InMemoryReferenceData;
import dev.berth9.engine.ref.ReferenceDataLoader;
import dev.berth9.engine.rules.RuleEngine;
import dev.berth9.engine.rules.ValidationContext;
import dev.berth9.engine.suggest.MappingSuggester;
import dev.berth9.engine.suggest.Suggestion;
import dev.berth9.engine.suggest.SuggestionReport;
import dev.berth9.engine.transform.TransformContext;
import dev.berth9.engine.transform.TransformRegistry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the engine without the server, against a config directory laid out like the server's
 * {@code berth9/} resources (schemas/, rules/, specs/, lookups.json, reference/reference-data.json).
 *
 * <pre>
 *   process  --config DIR --partner ID [--today YYYY-MM-DD] [--json OUT] FILE
 *   suggest  --config DIR --schema NAME [--locale US|IN] FILE
 *   ack997   FILE
 * </pre>
 */
public final class Berth9Cli {

    private final Map<String, String> options = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8));
        if (args.length == 0) {
            System.err.println("usage: process|suggest|ack997 [options] FILE");
            System.exit(2);
        }
        Berth9Cli cli = new Berth9Cli();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                cli.options.put(args[i].substring(2), args[++i]);
            } else {
                cli.positional.add(args[i]);
            }
        }
        switch (args[0]) {
            case "process" -> cli.process();
            case "suggest" -> cli.suggest();
            case "ack997" -> cli.ack997();
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    private void process() throws IOException {
        Path config = Path.of(options.getOrDefault("config", "."));
        String partner = required("partner");
        Path file = Path.of(positional.get(0));
        MappingSpec spec = ConfigLoader.spec(json(config.resolve("specs/" + partner + ".json")));
        TargetSchema schema = ConfigLoader.schema(json(config.resolve("schemas/" + spec.schema() + ".json")));
        var rules = ConfigLoader.rules(json(config.resolve("rules/" + spec.schema() + ".json")));
        TransformContext lookups = new TransformContext(ConfigLoader.lookups(json(config.resolve("lookups.json"))));
        InMemoryReferenceData reference = ReferenceDataLoader.load(json(config.resolve("reference/reference-data.json")));
        LocalDate today = options.containsKey("today") ? LocalDate.parse(options.get("today")) : LocalDate.now();

        RecordMapper mapper = new RecordMapper(spec, schema, TransformRegistry.standard(), lookups);
        Pipeline pipeline = new Pipeline(mapper, rules, new RuleEngine());
        byte[] bytes = Files.readAllBytes(file);
        SourceFormat format = spec.format() != SourceFormat.UNKNOWN ? spec.format() : FormatSniffer.detect(file.toString(), bytes);
        List<Map<String, Object>> exported = new ArrayList<>();
        try (RecordReader reader = RecordReaders.withDefaults().open(format, new ByteArrayInputStream(bytes), spec.reader())) {
            FileResult result = pipeline.run(reader, new ValidationContext(reference, today), outcome -> {
                print(outcome);
                exported.add(export(outcome));
            });
            System.out.printf(Locale.ROOT, "%n%s: %d records, %d valid, %d warnings, %d errors in %d ms%n",
                    file.getFileName(), result.total(), result.valid(), result.warnings(), result.errors(), result.elapsedMillis());
            result.fileIssues().forEach(v -> System.out.println("  FILE " + v.ruleId() + ": " + v.message()));
            if (options.containsKey("json")) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("partner", partner);
                doc.put("file", file.getFileName().toString());
                doc.put("format", format.name());
                doc.put("columns", reader.columns());
                doc.put("total", result.total());
                doc.put("valid", result.valid());
                doc.put("warnings", result.warnings());
                doc.put("errors", result.errors());
                doc.put("fileIssues", result.fileIssues().stream().map(Berth9Cli::violation).toList());
                doc.put("records", exported);
                Files.writeString(Path.of(options.get("json")), Json.pretty(doc));
            }
        }
    }

    private void suggest() throws IOException {
        Path config = Path.of(options.getOrDefault("config", "."));
        TargetSchema schema = ConfigLoader.schema(json(config.resolve("schemas/" + required("schema") + ".json")));
        Path file = Path.of(positional.get(0));
        byte[] bytes = Files.readAllBytes(file);
        SourceFormat format = FormatSniffer.detect(file.toString(), bytes);
        ReaderOptions readerOptions = ReaderOptions.defaults();
        if (options.containsKey("record")) {
            readerOptions = format == SourceFormat.X12 ? readerOptions.withRecordSegment(options.get("record"))
                    : readerOptions.withRecordElement(options.get("record"));
        }
        List<SourceRecord> sample = new ArrayList<>();
        List<String> columns;
        try (RecordReader reader = RecordReaders.withDefaults().open(format, new ByteArrayInputStream(bytes), readerOptions)) {
            while (reader.hasNext() && sample.size() < 200) {
                sample.add(reader.next());
            }
            columns = reader.columns();
        }
        SuggestionReport report = new MappingSuggester().suggest(schema, columns, sample, options.get("locale"));
        System.out.println("format " + format + ", " + sample.size() + " sample records, columns " + columns);
        for (Suggestion s : report.suggestions()) {
            System.out.printf(Locale.ROOT, "  %-14s <- %-40s %.2f %-6s %s%n", s.field(),
                    s.sources().isEmpty() ? "-" : String.join(" + ", s.sources()), s.confidence(), s.band(),
                    s.transform() == null ? "" : "[" + s.transform() + "]");
            s.reasons().forEach(r -> System.out.println("                   · " + r));
        }
        System.out.println("  unused columns: " + report.unusedColumns());
        System.out.println("  missing required: " + report.missingRequired());
    }

    private void ack997() throws IOException {
        String content = Files.readString(Path.of(positional.get(0)), StandardCharsets.UTF_8);
        X12Interchange interchange = new X12Parser().parse(content);
        interchange.issues().forEach(i -> System.err.println("interchange issue: " + i.message()));
        interchange.groups().forEach(g -> g.issues().forEach(i -> System.err.println("group issue: " + i.message())));
        interchange.transactions().forEach(t -> t.issues().forEach(i -> System.err.println("transaction " + t.controlNumber() + ": " + i.message())));
        System.out.print(Ack997.build(interchange, 1, 1, LocalDateTime.now()));
    }

    private static void print(RecordOutcome o) {
        Map<String, Object> v = o.mapped().values();
        System.out.printf(Locale.ROOT, "line %-4d %-8s %s%n", o.source().line(), o.status(), summary(v));
        for (Violation violation : o.violations()) {
            System.out.printf("           %-7s %-20s %s%n", violation.severity(), violation.ruleId(), violation.message());
        }
    }

    private static String summary(Map<String, Object> v) {
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("invoiceNumber", "poNumber", "lineNumber", "sku", "quantity", "unitPrice", "lineAmount", "currency", "lineAmountUsd")) {
            if (v.containsKey(key)) {
                sb.append(key).append('=').append(v.get(key)).append(' ');
            }
        }
        return sb.toString().strip();
    }

    private static Map<String, Object> export(RecordOutcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", o.source().line());
        m.put("status", o.status().name());
        m.put("source", o.source().fields());
        m.put("values", o.mapped().values());
        m.put("lineage", o.mapped().lineage());
        m.put("violations", o.violations().stream().map(Berth9Cli::violation).toList());
        return m;
    }

    private static Map<String, Object> violation(Violation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", v.ruleId());
        m.put("field", v.field());
        m.put("severity", v.severity().name());
        m.put("message", v.message());
        return m;
    }

    private String required(String name) {
        String v = options.get(name);
        if (v == null) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return v;
    }

    private static Map<String, Object> json(Path path) throws IOException {
        return Json.parseObject(Files.readString(path, StandardCharsets.UTF_8));
    }
}
