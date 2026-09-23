package dev.berth9.server.processing;

import dev.berth9.engine.format.FormatSniffer;
import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.read.ReaderOptions;
import dev.berth9.engine.read.RecordReader;
import dev.berth9.engine.read.RecordReaders;
import dev.berth9.engine.suggest.MappingSuggester;
import dev.berth9.engine.suggest.Suggestion;
import dev.berth9.engine.suggest.SuggestionReport;
import dev.berth9.server.ai.LlmMappingAdvisor;
import dev.berth9.server.catalog.ConfigCatalog;
import dev.berth9.server.store.Partner;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Onboarding a new partner: profile a sample file, suggest field mappings (rules first, a language model
 * only for what the rules could not settle) and return a draft spec an analyst reviews in the console.
 */
@Service
public class MappingService {

    private static final int SAMPLE = 200;

    private final RecordReaders readers;
    private final MappingSuggester suggester;
    private final LlmMappingAdvisor llm;
    private final ConfigCatalog catalog;

    public MappingService(RecordReaders readers, MappingSuggester suggester, LlmMappingAdvisor llm, ConfigCatalog catalog) {
        this.readers = readers;
        this.suggester = suggester;
        this.llm = llm;
        this.catalog = catalog;
    }

    public Map<String, Object> suggest(String schemaName, String fileName, byte[] content, String recordPath, Partner partner,
                                       String locale) throws IOException {
        TargetSchema schema = catalog.schema(schemaName);
        SourceFormat format = FormatSniffer.detect(fileName, content);
        ReaderOptions options = ReaderOptions.defaults();
        if (recordPath != null && !recordPath.isBlank()) {
            options = format == SourceFormat.X12 ? options.withRecordSegment(recordPath) : options.withRecordElement(recordPath);
        } else if (format == SourceFormat.XML || format == SourceFormat.JSON) {
            throw new IllegalArgumentException("XML and JSON samples need the record element (e.g. InvoiceDetailItem or lines)");
        }
        List<SourceRecord> sample = new ArrayList<>();
        List<String> columns;
        try (RecordReader reader = readers.open(format, new ByteArrayInputStream(content), options)) {
            while (reader.hasNext() && sample.size() < SAMPLE) {
                sample.add(reader.next());
            }
            columns = reader.columns();
        }
        String effectiveLocale = locale != null ? locale : partner != null ? partner.locale() : null;
        SuggestionReport report = suggester.suggest(schema, columns, sample, effectiveLocale);

        List<Suggestion> suggestions = new ArrayList<>(report.suggestions());
        Set<String> claimed = new HashSet<>();
        suggestions.forEach(s -> claimed.addAll(s.sources()));
        List<Suggestion> unresolved = suggestions.stream().filter(s -> !s.resolved()).toList();
        List<Suggestion> fromModel = llm.advise(schema, unresolved, report.profiles(), claimed);
        for (Suggestion s : fromModel) {
            suggestions.replaceAll(existing -> existing.field().equals(s.field()) && !existing.matched() ? s : existing);
        }
        suggestions.replaceAll(s -> fillFromProfile(s, schema, partner, suggestions));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema.name());
        out.put("format", format.name());
        out.put("records", sample.size());
        out.put("columns", columns);
        out.put("suggestions", suggestions);
        out.put("unusedColumns", report.unusedColumns());
        out.put("profiles", report.profiles());
        out.put("llm", Map.of("enabled", llm.enabled(), "model", llm.enabled() ? llm.model() : "", "used", !fromModel.isEmpty()));
        out.put("draftSpec", draftSpec(schema, format, options, suggestions, partner));
        return out;
    }

    /** Constants the partner profile already answers (who sent it, which currency) and derivable amounts. */
    private static Suggestion fillFromProfile(Suggestion s, TargetSchema schema, Partner partner, List<Suggestion> all) {
        if (s.matched()) {
            return s;
        }
        if (partner != null && (s.field().equals("supplierId") || s.field().equals("customerId"))) {
            return new Suggestion(s.field(), List.of(), null, partner.id(), null, 1.0, "HIGH", null,
                    List.of("sender is known from the channel (" + partner.id() + ")"), "profile");
        }
        if (partner != null && s.field().equals("currency")) {
            return new Suggestion(s.field(), List.of(), null, partner.currency(), null, 0.9, "HIGH", null,
                    List.of("partner invoices in " + partner.currency()), "profile");
        }
        boolean hasQty = all.stream().anyMatch(o -> o.field().equals("quantity") && o.matched());
        boolean hasPrice = all.stream().anyMatch(o -> o.field().equals("unitPrice") && o.matched());
        if (s.field().equals("lineAmount") && hasQty && hasPrice && schema.field("lineAmount").isPresent()) {
            return new Suggestion(s.field(), List.of(), null, null, "quantity * unitPrice", 0.85, "HIGH", null,
                    List.of("not in the file; computed as quantity x unit price"), "rules");
        }
        return s;
    }

    static Map<String, Object> draftSpec(TargetSchema schema, SourceFormat format, ReaderOptions options,
                                         List<Suggestion> suggestions, Partner partner) {
        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> enrich = new ArrayList<>();
        for (Suggestion s : suggestions) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("target", s.field());
            if (s.constant() != null) {
                f.put("const", s.constant());
            } else if (s.expression() != null) {
                enrich.add(Map.of("type", "compute", "target", s.field(), "left", "quantity", "op", "*", "right", "unitPrice", "scale", 2));
                continue;
            } else if (s.matched()) {
                if (s.sources().size() == 1) {
                    f.put("source", s.sources().get(0));
                } else {
                    f.put("sources", s.sources());
                    f.put("join", s.join());
                }
                if (s.transform() != null) {
                    f.put("transform", s.transform());
                }
            } else {
                continue;
            }
            fields.add(f);
        }
        if (schema.field("lineAmountUsd").isPresent()) {
            enrich.add(Map.of("type", "convert", "amount", "lineAmount", "currency", "currency", "target", "lineAmountUsd", "base", "USD"));
        }
        Map<String, Object> reader = new LinkedHashMap<>();
        if (options.recordElement() != null) {
            reader.put("recordElement", options.recordElement());
        }
        if (options.recordSegment() != null) {
            reader.put("recordSegment", options.recordSegment());
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("partnerId", partner == null ? "NEW-PARTNER" : partner.id());
        spec.put("schema", schema.name());
        spec.put("format", format.name());
        spec.put("reader", reader);
        spec.put("fields", fields);
        spec.put("enrich", enrich);
        return spec;
    }
}
