package dev.berth9.server.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PartnerStore {

    private static final String COLUMNS = "id, name, country, city, role, channel, format, document, schema_name, currency, "
            + "gstin, edi_id, locale, expected_by, api_key";

    private static final RowMapper<Partner> MAPPER = (rs, n) -> new Partner(rs.getString("id"), rs.getString("name"),
            rs.getString("country"), rs.getString("city"), rs.getString("role"), rs.getString("channel"), rs.getString("format"),
            rs.getString("document"), rs.getString("schema_name"), rs.getString("currency"), rs.getString("gstin"),
            rs.getString("edi_id"), rs.getString("locale"), rs.getString("expected_by"), rs.getString("api_key"));

    private final JdbcClient jdbc;

    public PartnerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Partner> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM partner ORDER BY role DESC, name").query(MAPPER).list();
    }

    public Optional<Partner> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM partner WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<Partner> findByApiKey(String apiKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM partner WHERE api_key = :key").param("key", apiKey).query(MAPPER).optional();
    }

    public Optional<String> secret(String partnerId) {
        return jdbc.sql("SELECT api_secret FROM partner WHERE id = :id").param("id", partnerId).query(String.class).optional();
    }
}
