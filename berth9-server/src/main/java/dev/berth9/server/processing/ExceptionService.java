package dev.berth9.server.processing;

import dev.berth9.engine.map.Enricher;
import dev.berth9.engine.map.MappedRecord;
import dev.berth9.engine.map.MappingSpec;
import dev.berth9.engine.model.TargetField;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.model.Violation;
import dev.berth9.engine.pipeline.Pipeline;
import dev.berth9.engine.rules.RuleEngine;
import dev.berth9.engine.rules.ValidationContext;
import dev.berth9.engine.transform.Coercion;
import dev.berth9.engine.transform.TransformException;
import dev.berth9.server.catalog.ConfigCatalog;
import dev.berth9.server.events.EventHub;
import dev.berth9.server.ref.SqlReferenceData;
import dev.berth9.server.store.JobStore;
import dev.berth9.server.store.RecordStore;
import dev.berth9.server.store.RecordStore.StoredRecord;
import dev.berth9.server.store.SpecStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Human-in-the-loop handling of the exceptions queue: an operator corrects values, the record is
 * re-validated against the same rules and reference data, and if it now passes it flows into the
 * delivery outbox. Every action is audited.
 */
@Service
public class ExceptionService {

    private final RecordStore records;
    private final JobStore jobs;
    private final SpecStore specs;
    private final ConfigCatalog catalog;
    private final RuleEngine ruleEngine;
    private final SqlReferenceData reference;
    private final EventHub events;
    private final Enricher enricher = new Enricher();

    public ExceptionService(RecordStore records, JobStore jobs, SpecStore specs, ConfigCatalog catalog, RuleEngine ruleEngine,
                            SqlReferenceData reference, EventHub events) {
        this.records = records;
        this.jobs = jobs;
        this.specs = specs;
        this.catalog = catalog;
        this.ruleEngine = ruleEngine;
        this.reference = reference;
        this.events = events;
    }

    @Transactional
    public StoredRecord fix(long id, Map<String, Object> edits, String actor) {
        StoredRecord record = load(id);
        TargetSchema schema = catalog.schema(record.schema());
        Map<String, Object> values = new LinkedHashMap<>(record.values());
        Map<String, Object> changes = new LinkedHashMap<>();
        edits.forEach((field, value) -> {
            if (schema.field(field).isEmpty()) {
                throw new IllegalArgumentException("unknown field " + field);
            }
            changes.put(field, Map.of("from", String.valueOf(values.get(field)), "to", String.valueOf(value)));
            values.put(field, value == null || value.toString().isBlank() ? null : value);
        });
        List<Violation> issues = new ArrayList<>();
        Map<String, Object> typed = new LinkedHashMap<>();
        for (TargetField field : schema.fields()) {
            Object value = values.get(field.name());
            try {
                typed.put(field.name(), Coercion.coerce(value, field.type()));
            } catch (TransformException | IllegalArgumentException | ArithmeticException e) {
                typed.put(field.name(), null);
                issues.add(Violation.error("MAPPING", field.name(), field.label() + ": " + e.getMessage()));
            }
        }
        MappingSpec spec = specs.latest(record.partnerId()).orElseThrow();
        Map<String, List<String>> lineage = new LinkedHashMap<>();
        record.lineage().forEach((k, v) -> lineage.put(k, v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(String.valueOf(v))));
        edits.keySet().forEach(f -> lineage.put(f, List.of("edited by " + actor)));
        MappedRecord mapped = enricher.apply(new MappedRecord(record.line(), typed, lineage, issues), spec.enrichments(), reference);
        List<Violation> violations = ruleEngine.evaluate(mapped, catalog.rules(schema.name()),
                new ValidationContext(reference, LocalDate.now(ZoneOffset.UTC)));
        String status = Pipeline.statusOf(violations).name();
        records.update(id, status, mapped.values(), violations.stream().map(ProcessingService::violation).toList());
        records.audit(id, "EDIT", Map.of("changes", changes, "result", status), actor);
        jobs.refreshCounts(record.jobId());
        events.publish("record.fixed", Map.of("recordId", id, "partnerId", record.partnerId(), "status", status,
                "remaining", violations.size()));
        return load(id);
    }

    @Transactional
    public StoredRecord dismiss(long id, String reason, String actor) {
        StoredRecord record = load(id);
        records.setStatus(id, "DISMISSED");
        records.audit(id, "DISMISS", Map.of("reason", reason == null ? "" : reason), actor);
        jobs.refreshCounts(record.jobId());
        events.publish("record.dismissed", Map.of("recordId", id, "partnerId", record.partnerId()));
        return load(id);
    }

    /** Sends a dead-lettered record back to the outbox after the downstream problem is fixed. */
    @Transactional
    public StoredRecord replay(long id, String actor) {
        StoredRecord record = load(id);
        if (!"DEAD_LETTER".equals(record.status())) {
            throw new IllegalStateException("only dead-lettered records can be replayed");
        }
        String status = record.violations().isEmpty() ? "VALID" : "WARNING";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) (List<?>) record.violations();
        records.update(id, status, record.values(), violations);
        records.audit(id, "REPLAY", Map.of(), actor);
        jobs.refreshCounts(record.jobId());
        events.publish("record.replayed", Map.of("recordId", id, "partnerId", record.partnerId()));
        return load(id);
    }

    private StoredRecord load(long id) {
        return records.find(id).orElseThrow(() -> new NoSuchElementException("record " + id + " not found"));
    }
}
