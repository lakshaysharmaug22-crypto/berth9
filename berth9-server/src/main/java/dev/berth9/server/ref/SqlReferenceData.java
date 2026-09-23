package dev.berth9.server.ref;

import dev.berth9.engine.ref.ReferenceData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reference data the rules check against, straight from the buyer's tables. Each dataset is one
 * indexed primary-key lookup; aliases return the camelCase field names the rule files use.
 */
@Component
public class SqlReferenceData implements ReferenceData {

    private final JdbcClient jdbc;

    public SqlReferenceData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Map<String, Object>> find(String dataset, String key) {
        String[] parts = key.split("\\|", -1);
        return switch (dataset) {
            case "po" -> one("""
                    SELECT po_number AS "poNumber", supplier_id AS "supplierId", currency, status
                    FROM purchase_order WHERE po_number = :a""", parts[0], null);
            case "po-line" -> parts.length < 2 ? Optional.empty() : one("""
                    SELECT po_number AS "poNumber", sku, ordered_qty AS "orderedQty", invoiced_qty AS "invoicedQty",
                           unit_price AS "unitPrice", uom
                    FROM po_line WHERE po_number = :a AND sku = :b""", parts[0], parts[1]);
            case "catalog" -> one("""
                    SELECT sku, description, uom, list_price AS "listPrice", currency FROM product WHERE sku = :a""", parts[0], null);
            case "ship-to" -> parts.length < 2 ? Optional.empty() : one("""
                    SELECT customer_id AS "customerId", ship_to_code AS "shipToCode", name, city, state
                    FROM ship_to WHERE customer_id = :a AND ship_to_code = :b""", parts[0], parts[1]);
            case "fx" -> one("SELECT currency, usd_rate AS \"rate\" FROM fx_rate WHERE currency = :a", parts[0], null);
            case "invoiced-lines", "ordered-lines" -> jdbc.sql("""
                            SELECT record_id AS "recordId" FROM delivered_key WHERE dataset = :d AND business_key = :k""")
                    .param("d", dataset).param("k", key).query().listOfRows().stream().findFirst();
            default -> Optional.empty();
        };
    }

    private Optional<Map<String, Object>> one(String sql, String a, String b) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("a", a);
        if (b != null) {
            spec = spec.param("b", b);
        }
        List<Map<String, Object>> rows = spec.query().listOfRows();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
