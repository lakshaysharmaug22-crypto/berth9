package dev.berth9.server.store;

import dev.berth9.engine.config.ConfigLoader;
import dev.berth9.engine.config.Json;
import dev.berth9.engine.map.MappingSpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Versioned partner mapping specs. Saving never overwrites: it adds version n+1. */
@Repository
public class SpecStore {

    public record SpecVersion(String partnerId, int version, String note, String createdBy, String createdAt, Map<String, Object> spec) {
    }

    private final JdbcClient jdbc;

    public SpecStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MappingSpec> latest(String partnerId) {
        return latestJson(partnerId).map(json -> ConfigLoader.spec(Json.parseObject(json)));
    }

    public Optional<String> latestJson(String partnerId) {
        return jdbc.sql("SELECT spec_json FROM mapping_spec WHERE partner_id = :p ORDER BY version DESC LIMIT 1")
                .param("p", partnerId).query(String.class).optional();
    }

    public List<SpecVersion> versions(String partnerId) {
        return jdbc.sql("SELECT partner_id, version, note, created_by, created_at, spec_json FROM mapping_spec "
                        + "WHERE partner_id = :p ORDER BY version DESC")
                .param("p", partnerId)
                .query((rs, n) -> new SpecVersion(rs.getString("partner_id"), rs.getInt("version"), rs.getString("note"),
                        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant().toString(),
                        Json.parseObject(rs.getString("spec_json"))))
                .list();
    }

    /** Validates the spec by building it, then stores it as the next version. Returns the new version number. */
    @Transactional
    public int save(String partnerId, Map<String, Object> spec, String note, String actor) {
        int next = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM mapping_spec WHERE partner_id = :p")
                .param("p", partnerId).query(Integer.class).single();
        Map<String, Object> stored = new LinkedHashMap<>(spec);
        stored.put("partnerId", partnerId);
        stored.put("version", next);
        ConfigLoader.spec(stored);
        jdbc.sql("INSERT INTO mapping_spec (partner_id, version, spec_json, note, created_by) VALUES (:p, :v, :json, :note, :by)")
                .param("p", partnerId).param("v", next).param("json", Json.pretty(stored)).param("note", note).param("by", actor)
                .update();
        return next;
    }

    public boolean exists(String partnerId) {
        return jdbc.sql("SELECT COUNT(*) FROM mapping_spec WHERE partner_id = :p").param("p", partnerId)
                .query(Integer.class).single() > 0;
    }
}
