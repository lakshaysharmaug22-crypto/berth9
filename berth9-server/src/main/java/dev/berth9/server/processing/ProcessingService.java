package dev.berth9.server.processing;

import dev.berth9.engine.edi.Ack997;
import dev.berth9.engine.edi.X12Interchange;
import dev.berth9.engine.format.FormatSniffer;
import dev.berth9.engine.format.SourceFormat;
import dev.berth9.engine.map.KeyTemplate;
import dev.berth9.engine.map.MappingSpec;
import dev.berth9.engine.map.RecordMapper;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.model.Violation;
import dev.berth9.engine.pipeline.FileResult;
import dev.berth9.engine.pipeline.Pipeline;
import dev.berth9.engine.pipeline.RecordOutcome;
import dev.berth9.engine.read.RecordReader;
import dev.berth9.engine.read.RecordReaders;
import dev.berth9.engine.read.X12RecordReader;
import dev.berth9.engine.rules.RuleEngine;
import dev.berth9.engine.rules.ValidationContext;
import dev.berth9.engine.transform.TransformRegistry;
import dev.berth9.server.catalog.ConfigCatalog;
import dev.berth9.server.config.Berth9Properties;
import dev.berth9.server.events.EventHub;
import dev.berth9.server.ref.SqlReferenceData;
import dev.berth9.server.store.JobStore;
import dev.berth9.server.store.Partner;
import dev.berth9.server.store.PartnerStore;
import dev.berth9.server.store.RecordStore;
import dev.berth9.server.store.SpecStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

/**
 * Processes one partner file end to end: idempotency check, format detection, streaming
 * map/enrich/validate through the engine, batched SQL writes, EDI 997 acknowledgment and live events.
 * Every channel (SFTP, folder drop, REST upload, API push) ends up here.
 */
@Service
public class ProcessingService {

    public record Result(long jobId, String partnerId, String fileName, String status, long total, long valid, long warnings,
                         long errors, boolean duplicate, String message) {
    }

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);
    private static final int BATCH = 250;

    private final PartnerStore partners;
    private final SpecStore specs;
    private final JobStore jobs;
    private final RecordStore records;
    private final ConfigCatalog catalog;
    private final RecordReaders readers;
    private final TransformRegistry transforms;
    private final RuleEngine ruleEngine;
    private final SqlReferenceData reference;
    private final EventHub events;
    private final MeterRegistry meters;
    private final JdbcClient jdbc;
    private final Berth9Properties props;

    public ProcessingService(PartnerStore partners, SpecStore specs, JobStore jobs, RecordStore records, ConfigCatalog catalog,
                             RecordReaders readers, TransformRegistry transforms, RuleEngine ruleEngine,
                             SqlReferenceData reference, EventHub events, MeterRegistry meters, JdbcClient jdbc,
                             Berth9Properties props) {
        this.partners = partners;
        this.specs = specs;
        this.jobs = jobs;
        this.records = records;
        this.catalog = catalog;
        this.readers = readers;
        this.transforms = transforms;
        this.ruleEngine = ruleEngine;
        this.reference = reference;
        this.events = events;
        this.meters = meters;
        this.jdbc = jdbc;
        this.props = props;
    }

    public Result process(String partnerId, String fileName, byte[] content, String channel) {
        Partner partner = partners.find(partnerId).orElseThrow(() -> new NoSuchElementException("unknown partner " + partnerId));
        String sha = sha256(content);
        var existing = jobs.findByContent(partner.id(), sha);
        if (existing.isPresent()) {
            var job = existing.get();
            events.publish("file.duplicate", Map.of("partnerId", partner.id(), "fileName", fileName, "jobId", job.id(),
                    "originalFile", job.fileName()));
            meters.counter("berth9.files", "partner", partner.id(), "status", "DUPLICATE").increment();
            return new Result(job.id(), partner.id(), fileName, "DUPLICATE", job.total(), job.valid(), job.warnings(), job.errors(),
                    true, "identical content already received as job " + job.id() + " (" + job.fileName() + ")");
        }

        long started = System.nanoTime();
        long jobId = jobs.start(partner.id(), fileName, sha, channel, content.length);
        events.publish("file.received", ordered("jobId", jobId, "partnerId", partner.id(), "fileName", fileName,
                "channel", channel, "bytes", content.length));
        try {
            MappingSpec spec = specs.latest(partner.id())
                    .orElseThrow(() -> new IllegalStateException("partner " + partner.id() + " has no mapping spec yet"));
            SourceFormat format = spec.format() != SourceFormat.UNKNOWN ? spec.format() : FormatSniffer.detect(fileName, content);
            jobs.describe(jobId, format.name(), spec.version());
            events.publish("file.detected", ordered("jobId", jobId, "partnerId", partner.id(), "format", format.name(),
                    "specVersion", spec.version()));

            TargetSchema schema = catalog.schema(spec.schema());
            Pipeline pipeline = new Pipeline(new RecordMapper(spec, schema, transforms, catalog.lookups()),
                    catalog.rules(schema.name()), ruleEngine);
            ValidationContext context = new ValidationContext(reference, LocalDate.now(ZoneOffset.UTC));

            List<RecordStore.NewRecord> batch = new ArrayList<>(BATCH);
            long[] seen = {0};
            FileResult result;
            String ack = null;
            Map<String, String> fileContext;
            try (RecordReader reader = readers.open(format, new ByteArrayInputStream(content), spec.reader())) {
                result = pipeline.run(reader, context, outcome -> {
                    batch.add(toRow(jobId, schema, outcome));
                    seen[0]++;
                    if (batch.size() >= BATCH) {
                        records.insertBatch(batch);
                        batch.clear();
                        events.publish("file.progress", ordered("jobId", jobId, "partnerId", partner.id(), "records", seen[0]));
                    }
                });
                records.insertBatch(batch);
                fileContext = new LinkedHashMap<>(reader.fileContext());
                if (reader instanceof X12RecordReader x12) {
                    ack = acknowledge(partner, x12.interchange(), jobId, fileName);
                }
            }

            String status = result.errors() == 0 ? "COMPLETED" : "NEEDS_REVIEW";
            String message = result.errors() == 0 ? "all records passed validation"
                    : result.errors() + " of " + result.total() + " records need attention";
            if (result.total() == 0) {
                status = ack != null ? "REJECTED" : "FAILED";
                message = ack != null ? "every transaction set failed envelope checks; rejection sent in the 997"
                        : "no records found in file";
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            List<Map<String, Object>> issues = result.fileIssues().stream().map(ProcessingService::violation).toList();
            jobs.finish(jobId, status, result.total(), result.valid(), result.warnings(), result.errors(), issues,
                    fileContext, ack, message, elapsed);

            meters.counter("berth9.files", "partner", partner.id(), "status", status).increment();
            meters.counter("berth9.records", "partner", partner.id(), "status", "VALID").increment(result.valid());
            meters.counter("berth9.records", "partner", partner.id(), "status", "WARNING").increment(result.warnings());
            meters.counter("berth9.records", "partner", partner.id(), "status", "ERROR").increment(result.errors());
            Timer.builder("berth9.file.processing").tag("partner", partner.id()).register(meters).record(elapsed, TimeUnit.MILLISECONDS);

            Map<String, Object> done = ordered("jobId", jobId, "partnerId", partner.id(), "fileName", fileName, "status", status,
                    "total", result.total(), "valid", result.valid(), "warnings", result.warnings(), "errors", result.errors(),
                    "durationMs", elapsed);
            done.put("fileIssues", issues);
            events.publish("file.processed", done);
            log.info("{} {} -> {} ({} records, {} errors) in {} ms", partner.id(), fileName, status, result.total(), result.errors(), elapsed);
            return new Result(jobId, partner.id(), fileName, status, result.total(), result.valid(), result.warnings(),
                    result.errors(), false, message);
        } catch (RuntimeException | IOException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jobs.fail(jobId, message, elapsed);
            meters.counter("berth9.files", "partner", partner.id(), "status", "FAILED").increment();
            events.publish("file.failed", ordered("jobId", jobId, "partnerId", partner.id(), "fileName", fileName, "error", message));
            log.warn("{} {} failed: {}", partner.id(), fileName, message);
            return new Result(jobId, partner.id(), fileName, "FAILED", 0, 0, 0, 0, false, message);
        }
    }

    /** Builds the 997, stores it on the job and drops it in the partner's outbox folder. */
    private String acknowledge(Partner partner, X12Interchange interchange, long jobId, String fileName) throws IOException {
        long isa = nextCounter("ISA");
        long gs = nextCounter("GS");
        String ack = Ack997.build(interchange, isa, gs, LocalDateTime.now(ZoneOffset.UTC));
        Path outbox = Path.of(props.intake().outboxDir(), partner.id());
        Files.createDirectories(outbox);
        String name = "997_" + String.format("%09d", isa) + "_" + fileName.replaceAll("\\.(x12|edi|txt|dat)$", "").replaceAll("[^A-Za-z0-9._-]", "_") + ".x12";
        Files.writeString(outbox.resolve(name), ack, StandardCharsets.UTF_8);
        long accepted = interchange.transactions().stream().filter(t -> t.accepted()).count();
        events.publish("ack.sent", ordered("jobId", jobId, "partnerId", partner.id(), "type", "997",
                "accepted", accepted, "rejected", interchange.transactions().size() - accepted, "file", name));
        return ack;
    }

    private synchronized long nextCounter(String name) {
        jdbc.sql("UPDATE edi_counter SET counter_value = counter_value + 1 WHERE name = :n").param("n", name).update();
        return jdbc.sql("SELECT counter_value FROM edi_counter WHERE name = :n").param("n", name).query(Long.class).single();
    }

    static RecordStore.NewRecord toRow(long jobId, TargetSchema schema, RecordOutcome o) {
        String key = businessKey(schema, o.mapped().values());
        return new RecordStore.NewRecord(jobId, o.source().line(), key, o.status().name(), o.source().fields(),
                o.mapped().values(), o.mapped().lineage(), o.violations().stream().map(ProcessingService::violation).toList());
    }

    public static String businessKey(TargetSchema schema, Map<String, Object> values) {
        if (schema.businessKey().isEmpty()) {
            return null;
        }
        String template = String.join("|", schema.businessKey().stream().map(f -> "{" + f + "}").toList());
        return KeyTemplate.render(template, values);
    }

    public static Map<String, Object> violation(Violation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", v.ruleId());
        m.put("field", v.field());
        m.put("severity", v.severity().name());
        m.put("message", v.message());
        return m;
    }

    static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
