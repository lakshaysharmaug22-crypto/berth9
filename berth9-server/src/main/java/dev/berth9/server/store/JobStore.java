package dev.berth9.server.store;

import dev.berth9.engine.config.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Received files ({@code file_job}). */
@Repository
public class JobStore {

    public record Job(long id, String partnerId, String fileName, String sha256, String channel, String format,
                      Integer specVersion, String status, int total, int valid, int warnings, int errors, Long sizeBytes,
                      Object fileIssues, Object context, boolean hasAck, String message, String receivedAt,
                      String completedAt, Long durationMs) {
    }

    private static final String COLUMNS = "id, partner_id, file_name, sha256, channel, format, spec_version, status, total, valid, "
            + "warnings, errors, size_bytes, file_issues, context_json, ack_997 IS NOT NULL AS has_ack, message, received_at, "
            + "completed_at, duration_ms";

    private static final RowMapper<Job> MAPPER = JobStore::map;

    private final JdbcClient jdbc;

    public JobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Job> findByContent(String partnerId, String sha256) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM file_job WHERE partner_id = :p AND sha256 = :s")
                .param("p", partnerId).param("s", sha256).query(MAPPER).optional();
    }

    public long start(String partnerId, String fileName, String sha256, String channel, long size) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO file_job (partner_id, file_name, sha256, channel, status, size_bytes, received_at) "
                        + "VALUES (:p, :f, :s, :c, 'PROCESSING', :size, :now)")
                .param("p", partnerId).param("f", fileName).param("s", sha256).param("c", channel).param("size", size)
                .param("now", now())
                .update(keys, "id");
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("no id generated for file_job");
        }
        return key.longValue();
    }

    public void describe(long id, String format, Integer specVersion) {
        jdbc.sql("UPDATE file_job SET format = :f, spec_version = :v WHERE id = :id")
                .param("f", format).param("v", specVersion).param("id", id).update();
    }

    public void finish(long id, String status, long total, long valid, long warnings, long errors,
                       List<Map<String, Object>> fileIssues, Map<String, String> context, String ack997, String message, long durationMs) {
        jdbc.sql("UPDATE file_job SET status = :st, total = :t, valid = :v, warnings = :w, errors = :e, file_issues = :fi, "
                        + "context_json = :ctx, ack_997 = :ack, message = :m, completed_at = :now, duration_ms = :d WHERE id = :id")
                .param("st", status).param("t", total).param("v", valid).param("w", warnings).param("e", errors)
                .param("fi", Json.write(fileIssues)).param("ctx", Json.write(context)).param("ack", ack997)
                .param("m", truncate(message)).param("now", now()).param("d", durationMs).param("id", id)
                .update();
    }

    public void fail(long id, String message, long durationMs) {
        jdbc.sql("UPDATE file_job SET status = 'FAILED', message = :m, completed_at = :now, duration_ms = :d WHERE id = :id")
                .param("m", truncate(message)).param("now", now()).param("d", durationMs).param("id", id).update();
    }

    /** Recomputes the counters after records were fixed, dismissed or delivered. */
    public void refreshCounts(long id) {
        jdbc.sql("""
                UPDATE file_job SET
                  valid = (SELECT COUNT(*) FROM job_record r WHERE r.job_id = file_job.id AND r.status IN ('VALID', 'DELIVERED')
                           AND COALESCE(r.violations_json, '') NOT LIKE '%"WARNING"%'),
                  warnings = (SELECT COUNT(*) FROM job_record r WHERE r.job_id = file_job.id AND (r.status = 'WARNING'
                           OR (r.status = 'DELIVERED' AND COALESCE(r.violations_json, '') LIKE '%"WARNING"%'))),
                  errors = (SELECT COUNT(*) FROM job_record r WHERE r.job_id = file_job.id AND r.status IN ('ERROR', 'DEAD_LETTER'))
                WHERE id = :id""").param("id", id).update();
        jdbc.sql("UPDATE file_job SET status = 'COMPLETED' WHERE id = :id AND status = 'NEEDS_REVIEW' AND errors = 0")
                .param("id", id).update();
    }

    public Optional<Job> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM file_job WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<String> ack(long id) {
        return jdbc.sql("SELECT ack_997 FROM file_job WHERE id = :id").param("id", id).query(String.class).optional();
    }

    public List<Job> recent(int limit, String partnerId) {
        String where = partnerId == null ? "" : " WHERE partner_id = :p";
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT " + COLUMNS + " FROM file_job" + where + " ORDER BY id DESC LIMIT :limit")
                .param("limit", limit);
        if (partnerId != null) {
            spec = spec.param("p", partnerId);
        }
        return spec.query(MAPPER).list();
    }

    public Map<String, Object> totals() {
        return jdbc.sql("""
                SELECT COUNT(*) AS files, COALESCE(SUM(total), 0) AS records, COALESCE(SUM(valid), 0) AS valid,
                       COALESCE(SUM(warnings), 0) AS warnings, COALESCE(SUM(errors), 0) AS errors,
                       COALESCE(AVG(duration_ms), 0) AS avg_ms
                FROM file_job WHERE status <> 'DUPLICATE'""").query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("files", rs.getLong("files"));
            m.put("records", rs.getLong("records"));
            m.put("valid", rs.getLong("valid"));
            m.put("warnings", rs.getLong("warnings"));
            m.put("errors", rs.getLong("errors"));
            m.put("avgFileMs", Math.round(rs.getDouble("avg_ms")));
            return m;
        }).single();
    }

    /** Last file per partner, for SLA tracking on the partners page. */
    public Map<String, String> lastReceivedByPartner() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT partner_id, MAX(received_at) AS last_at FROM file_job GROUP BY partner_id")
                .query((rs, n) -> {
                    Timestamp t = rs.getTimestamp("last_at");
                    out.put(rs.getString("partner_id"), t == null ? null : t.toInstant().toString());
                    return null;
                }).list();
        return out;
    }

    private static Job map(ResultSet rs, int n) throws SQLException {
        String issues = rs.getString("file_issues");
        String context = rs.getString("context_json");
        Timestamp completed = rs.getTimestamp("completed_at");
        long size = rs.getLong("size_bytes");
        boolean sizeNull = rs.wasNull();
        long duration = rs.getLong("duration_ms");
        boolean durationNull = rs.wasNull();
        int specVersion = rs.getInt("spec_version");
        boolean specNull = rs.wasNull();
        return new Job(rs.getLong("id"), rs.getString("partner_id"), rs.getString("file_name"), rs.getString("sha256"),
                rs.getString("channel"), rs.getString("format"), specNull ? null : specVersion, rs.getString("status"),
                rs.getInt("total"), rs.getInt("valid"), rs.getInt("warnings"), rs.getInt("errors"), sizeNull ? null : size,
                issues == null ? List.of() : Json.parse(issues), context == null ? Map.of() : Json.parse(context),
                rs.getBoolean("has_ack"), rs.getString("message"), rs.getTimestamp("received_at").toInstant().toString(),
                completed == null ? null : completed.toInstant().toString(), durationNull ? null : duration);
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }
}
