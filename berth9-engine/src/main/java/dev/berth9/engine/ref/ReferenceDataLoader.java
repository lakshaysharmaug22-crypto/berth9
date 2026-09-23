package dev.berth9.engine.ref;

import dev.berth9.engine.map.KeyTemplate;

import java.util.List;
import java.util.Map;

/** Loads reference datasets from JSON-shaped data ({@code {"po": [{...}, ...], ...}}) into memory. */
public final class ReferenceDataLoader {

    /** Key template per dataset; the server uses the same keys against SQL tables. */
    public static final Map<String, String> KEYS = Map.of(
            "po", "{poNumber}",
            "po-line", "{poNumber}|{sku}",
            "catalog", "{sku}",
            "ship-to", "{customerId}|{shipToCode}",
            "fx", "{currency}");

    private ReferenceDataLoader() {
    }

    @SuppressWarnings("unchecked")
    public static InMemoryReferenceData load(Map<String, Object> json) {
        InMemoryReferenceData data = new InMemoryReferenceData();
        json.forEach((dataset, rows) -> {
            String template = KEYS.get(dataset);
            if (template == null || !(rows instanceof List<?> list)) {
                return;
            }
            for (Object row : list) {
                Map<String, Object> values = (Map<String, Object>) row;
                String key = KeyTemplate.render(template, values);
                if (key != null) {
                    data.put(dataset, key, values);
                }
            }
        });
        return data;
    }
}
