package dev.berth9.server.store;

import dev.berth9.engine.config.Json;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Records of every file ({@code job_record}): exceptions queue and delivery outbox in one table. */
@Repository
public class RecordStore {

    public record NewRecord(long jobId, long line, String businessKey, String status, Map<String, String> source,
                            Map<String, Object> values, Map<String, List<String>> lineage, List<Map<String, Object>> violations) {
    }

    public record StoredRecord(long id, long jobId, String partnerId, String schema, long line, String businessKey, String status,
                               Map<String, Object> source, Map<String, Object> values, Map<String, Object> lineage,
                               List<Object> violations, int deliveryAttempts, String lastError, String deliveredAt,
                               String updatedAt, String fileName) {
    }

    private static final String SELECT = """
            SELECT r.id, r.job_id, j.partner_id, p.schema_name, r.line_no, r.business_key, r.status, r.source_json, r.values_json,
                   r.lineage_json, r.violations_json, r.delivery_attempts, r.last_error, r.delivered_at, r.updated_at, j.file_name
            FROM job_record r JOIN file_job j ON j.id = r.job_id JOIN partner p ON p.id = j.partner_id
            """;

    @SuppressWarnings("unchecked")
    private static final RowMapper<StoredRecord> MAPPER = (rs, n) -> {
        Timestamp delivered = rs.getTimestamp("delivered_at");
        String violations = rs.getString("violations_json");
        return new StoredRecord(rs.getLong("id"), rs.getLong("job_id"), rs.getString("partner_id"), rs.getString("schema_name"),
                rs.getLong("line_no"), rs.getString("business_key"), rs.getString("status"),
                Json.parseObject(rs.getString("source_json")), parseMap(rs.getString("values_json")),
                parseMap(rs.getString("lineage_json")),
                violations == null ? List.of() : (List<Object>) Json.parse(violations),
                rs.getInt("delivery_attempts"), rs.getString("last_error"),
                delivered == null ? null : delivered.toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString(), rs.getString("file_name"));
    };

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    public RecordStore(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public void insertBatch(List<NewRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        OffsetDateTime now = JobStore.now();
        MapSqlParameterSource[] params = records.stream().map(r -> new MapSqlParameterSource()
                .addValue("job", r.jobId()).addValue("line", r.line()).addValue("key", r.businessKey())
                .addValue("status", r.status()).addValue("source", Json.write(r.source()))
                .addValue("vals", Json.write(r.values())).addValue("lineage", Json.write(r.lineage()))
                .addValue("violations", Json.write(r.violations()))
                .addValue("next", deliverable(r.status()) ? now : null).addValue("now", now)).toArray(MapSqlParameterSource[]::new);
        named.batchUpdate("""
                INSERT INTO job_record (job_id, line_no, business_key, status, source_json, values_json, lineage_json,
                                        violations_json, next_attempt_at, updated_at)
                VALUES (:job, :line, :key, :status, :source, :vals, :lineage, :violations, :next, :now)""", params);
    }

    public Optional<StoredRecord> find(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<StoredRecord> byJob(long jobId, String status, int limit) {
        String filter = status == null ? "" : " AND r.status = :status";
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT + " WHERE r.job_id = :job" + filter + " ORDER BY r.line_no LIMIT :limit")
                .param("job", jobId).param("limit", limit);
        if (status != null) {
            spec = spec.param("status", status);
        }
        return spec.query(MAPPER).list();
    }

    /** Open exceptions: records that failed validation or exhausted delivery attempts. */
    public List<StoredRecord> exceptions(int limit, String partnerId) {
        String filter = partnerId == null ? "" : " AND j.partner_id = :p";
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT + " WHERE r.status IN ('ERROR', 'DEAD_LETTER')" + filter
                + " ORDER BY r.id DESC LIMIT :limit").param("limit", limit);
        if (partnerId != null) {
            spec = spec.param("p", partnerId);
        }
        return spec.query(MAPPER).list();
    }

    /** Outbox claim: records ready for delivery, oldest first. Single-writer, so no row locking is needed. */
    public List<StoredRecord> dueForDelivery(int limit) {
        return jdbc.sql(SELECT + " WHERE r.status IN ('VALID', 'WARNING') AND r.next_attempt_at <= :now ORDER BY r.id LIMIT :limit")
                .param("now", JobStore.now()).param("limit", limit).query(MAPPER).list();
    }

    public void update(long id, String status, Map<String, Object> values, List<Map<String, Object>> violations) {
        jdbc.sql("UPDATE job_record SET status = :s, values_json = :v, violations_json = :viol, next_attempt_at = :next, "
                        + "delivery_attempts = 0, last_error = NULL, updated_at = :now WHERE id = :id")
                .param("s", status).param("v", Json.write(values)).param("viol", Json.write(violations))
                .param("next", deliverable(status) ? JobStore.now() : null).param("now", JobStore.now()).param("id", id)
                .update();
    }

    public void setStatus(long id, String status) {
        jdbc.sql("UPDATE job_record SET status = :s, next_attempt_at = :next, updated_at = :now WHERE id = :id")
                .param("s", status).param("next", deliverable(status) ? JobStore.now() : null).param("now", JobStore.now())
                .param("id", id).update();
    }

    public void markDelivered(long id) {
        jdbc.sql("UPDATE job_record SET status = 'DELIVERED', delivered_at = :now, next_attempt_at = NULL, last_error = NULL, "
                        + "delivery_attempts = delivery_attempts + 1, updated_at = :now WHERE id = :id")
                .param("now", JobStore.now()).param("id", id).update();
    }

    public void scheduleRetry(long id, int attempts, OffsetDateTime next, String error, boolean dead) {
        jdbc.sql("UPDATE job_record SET delivery_attempts = :a, next_attempt_at = :next, last_error = :err, status = :st, "
                        + "updated_at = :now WHERE id = :id")
                .param("a", attempts).param("next", dead ? null : next).param("err", error == null ? null
                        : error.length() > 1000 ? error.substring(0, 1000) : error)
                .param("st", dead ? "DEAD_LETTER" : currentStatus(id)).param("now", JobStore.now()).param("id", id).update();
    }

    /** Pushes the whole outbox back when the circuit breaker is open, without spending attempts. */
    public void postpone(List<Long> ids, OffsetDateTime until) {
        if (ids.isEmpty()) {
            return;
        }
        named.update("UPDATE job_record SET next_attempt_at = :until WHERE id IN (:ids)",
                new MapSqlParameterSource().addValue("until", until).addValue("ids", ids));
    }

    /** Remembers a delivered business key; returns false when it was already there (idempotent delivery). */
    public boolean rememberDelivered(String dataset, String businessKey, long recordId) {
        try {
            jdbc.sql("INSERT INTO delivered_key (dataset, business_key, record_id) VALUES (:d, :k, :r)")
                    .param("d", dataset).param("k", businessKey).param("r", recordId).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void consumePoQuantity(String poNumber, String sku, java.math.BigDecimal quantity) {
        jdbc.sql("UPDATE po_line SET invoiced_qty = invoiced_qty + :q WHERE po_number = :po AND sku = :sku")
                .param("q", quantity).param("po", poNumber).param("sku", sku).update();
    }

    public void audit(long recordId, String action, Map<String, Object> detail, String actor) {
        jdbc.sql("INSERT INTO record_audit (record_id, action, detail, actor) VALUES (:r, :a, :d, :by)")
                .param("r", recordId).param("a", action).param("d", Json.write(detail)).param("by", actor).update();
    }

    public List<Map<String, Object>> auditTrail(long recordId) {
        return jdbc.sql("SELECT action, detail, actor, acted_at FROM record_audit WHERE record_id = :r ORDER BY id")
                .param("r", recordId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("action", rs.getString("action"));
                    m.put("detail", Json.parse(rs.getString("detail")));
                    m.put("actor", rs.getString("actor"));
                    m.put("at", rs.getTimestamp("acted_at").toInstant().toString());
                    return m;
                }).list();
    }

    public Map<String, Long> countsByStatus() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("SELECT status, COUNT(*) AS n FROM job_record GROUP BY status")
                .query((rs, n) -> out.put(rs.getString("status"), rs.getLong("n"))).list();
        return out;
    }

    public List<Map<String, Object>> partnerStats() {
        return jdbc.sql("""
                SELECT j.partner_id,
                       COUNT(DISTINCT j.id) AS files,
                       COUNT(r.id) AS records,
                       SUM(CASE WHEN r.status = 'DELIVERED' THEN 1 ELSE 0 END) AS delivered,
                       SUM(CASE WHEN r.status IN ('ERROR', 'DEAD_LETTER') THEN 1 ELSE 0 END) AS open_errors,
                       SUM(CASE WHEN r.status = 'WARNING' THEN 1 ELSE 0 END) AS warnings
                FROM file_job j LEFT JOIN job_record r ON r.job_id = j.id
                GROUP BY j.partner_id""").query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partnerId", rs.getString("partner_id"));
            m.put("files", rs.getLong("files"));
            m.put("records", rs.getLong("records"));
            m.put("delivered", rs.getLong("delivered"));
            m.put("openErrors", rs.getLong("open_errors"));
            m.put("warnings", rs.getLong("warnings"));
            return m;
        }).list();
    }

    private String currentStatus(long id) {
        return jdbc.sql("SELECT status FROM job_record WHERE id = :id").param("id", id).query(String.class).single();
    }

    private static boolean deliverable(String status) {
        return "VALID".equals(status) || "WARNING".equals(status);
    }

    private static Map<String, Object> parseMap(String json) {
        return json == null ? Map.of() : Json.parseObject(json);
    }
}
