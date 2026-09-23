package dev.berth9.server.delivery;

import dev.berth9.engine.config.Json;
import dev.berth9.server.config.Berth9Properties;
import dev.berth9.server.events.EventHub;
import dev.berth9.server.store.JobStore;
import dev.berth9.server.store.RecordStore;
import dev.berth9.server.store.RecordStore.StoredRecord;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Transactional-outbox relay. Validated records are committed to {@code job_record} together with the
 * file; this relay (triggered by a Camel timer route) pushes them to the ERP in batches, behind a circuit
 * breaker, with exponential backoff and a dead-letter state after {@code maxAttempts}. Nothing is lost if
 * the ERP is down, and nothing is applied twice: every batch carries an Idempotency-Key and delivered
 * business keys are remembered.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final RecordStore records;
    private final JobStore jobs;
    private final EventHub events;
    private final MeterRegistry meters;
    private final ProducerTemplate camel;
    private final Berth9Properties.Delivery config;
    private final RestClient erp;
    private final CircuitBreaker breaker;

    public DeliveryService(RecordStore records, JobStore jobs, EventHub events, MeterRegistry meters, ProducerTemplate camel,
                           Berth9Properties props) {
        this.records = records;
        this.jobs = jobs;
        this.events = events;
        this.meters = meters;
        this.camel = camel;
        this.config = props.delivery();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.erp = RestClient.builder().baseUrl(config.erpBaseUrl()).requestFactory(factory).build();
        this.breaker = CircuitBreaker.of("erp", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(6)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        breaker.getEventPublisher().onStateTransition(e -> {
            String to = e.getStateTransition().getToState().name();
            events.publish("breaker.state", Map.of("target", "erp", "state", to));
            log.warn("ERP circuit breaker -> {}", to);
        });
    }

    /** Called by the outbox Camel route. Returns how many records were delivered. */
    public int deliverBatch() {
        List<StoredRecord> due = records.dueForDelivery(config.batchSize());
        if (due.isEmpty()) {
            return 0;
        }
        Map<String, List<StoredRecord>> bySchema = due.stream().collect(Collectors.groupingBy(StoredRecord::schema,
                LinkedHashMap::new, Collectors.toList()));
        int delivered = 0;
        for (Map.Entry<String, List<StoredRecord>> group : bySchema.entrySet()) {
            delivered += deliver(group.getKey(), group.getValue());
        }
        return delivered;
    }

    private int deliver(String schema, List<StoredRecord> batch) {
        String body = Json.write(batch.stream().map(DeliveryService::payload).toList());
        String idempotencyKey = sha256(batch.stream().map(r -> r.id() + ":" + r.businessKey()).collect(Collectors.joining(",")));
        long started = System.nanoTime();
        try {
            breaker.executeRunnable(() -> erp.post().uri("/{document}", schema).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey).body(body).retrieve().toBodilessEntity());
        } catch (CallNotPermittedException open) {
            records.postpone(batch.stream().map(StoredRecord::id).toList(), OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5));
            return 0;
        } catch (RuntimeException failure) {
            onFailure(batch, failure.getMessage());
            return 0;
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        Set<Long> touchedJobs = new LinkedHashSet<>();
        String dataset = "order-line".equals(schema) ? "ordered-lines" : "invoiced-lines";
        for (StoredRecord r : batch) {
            records.markDelivered(r.id());
            if (r.businessKey() != null) {
                records.rememberDelivered(dataset, r.businessKey(), r.id());
            }
            touchedJobs.add(r.jobId());
        }
        consumeOpenQuantity(schema, batch);
        touchedJobs.forEach(jobs::refreshCounts);
        publishEvents(schema, batch);
        meters.counter("berth9.delivered", "document", schema).increment(batch.size());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("document", schema);
        event.put("records", batch.size());
        event.put("partners", batch.stream().map(StoredRecord::partnerId).distinct().toList());
        event.put("latencyMs", ms);
        event.put("kafka", config.kafkaEnabled());
        events.publish("delivery.succeeded", event);
        return batch.size();
    }

    private void onFailure(List<StoredRecord> batch, String error) {
        List<Long> dead = new ArrayList<>();
        for (StoredRecord r : batch) {
            int attempts = r.deliveryAttempts() + 1;
            boolean isDead = attempts >= config.maxAttempts();
            long backoff = Math.min(60, (long) Math.pow(2, attempts));
            records.scheduleRetry(r.id(), attempts, OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(backoff), error, isDead);
            if (isDead) {
                dead.add(r.id());
            }
        }
        batch.stream().map(StoredRecord::jobId).distinct().forEach(jobs::refreshCounts);
        meters.counter("berth9.delivery.failures").increment();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("records", batch.size());
        event.put("error", error);
        event.put("deadLettered", dead.size());
        events.publish("delivery.failed", event);
    }

    /** Invoiced quantity is consumed on the PO so the next invoice is checked against what is still open. */
    private void consumeOpenQuantity(String schema, List<StoredRecord> batch) {
        if (!"invoice-line".equals(schema)) {
            return;
        }
        for (StoredRecord r : batch) {
            Object po = r.values().get("poNumber");
            Object sku = r.values().get("sku");
            Object qty = r.values().get("quantity");
            if (po != null && sku != null && qty != null) {
                records.consumePoQuantity(po.toString(), sku.toString(), new BigDecimal(qty.toString()));
            }
        }
    }

    private void publishEvents(String schema, List<StoredRecord> batch) {
        if (!config.kafkaEnabled()) {
            return;
        }
        String topic = config.kafkaTopicPrefix() + schema;
        for (StoredRecord r : batch) {
            camel.sendBodyAndHeader("kafka:" + topic, Json.write(payload(r)), "kafka.KEY", r.businessKey());
        }
    }

    static Map<String, Object> payload(StoredRecord r) {
        Map<String, Object> m = new LinkedHashMap<>(r.values());
        m.put("_recordId", r.id());
        m.put("_partner", r.partnerId());
        m.put("_warnings", r.violations().size());
        return m;
    }

    public Map<String, Object> breakerState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", breaker.getState().name());
        m.put("failureRate", breaker.getMetrics().getFailureRate());
        m.put("bufferedCalls", breaker.getMetrics().getNumberOfBufferedCalls());
        m.put("notPermittedCalls", breaker.getMetrics().getNumberOfNotPermittedCalls());
        return m;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
