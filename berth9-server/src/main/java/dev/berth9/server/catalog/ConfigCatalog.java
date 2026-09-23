package dev.berth9.server.catalog;

import dev.berth9.engine.config.ConfigLoader;
import dev.berth9.engine.config.Json;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.rules.Rule;
import dev.berth9.engine.transform.TransformContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Canonical schemas, rule sets and lookup tables, loaded once from {@code classpath:berth9/}.
 * They are versioned with the code; partner-specific mapping specs live in the database.
 */
@Component
public class ConfigCatalog {

    private final Map<String, TargetSchema> schemas = new LinkedHashMap<>();
    private final Map<String, List<Rule>> rules = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> rawSchemas = new LinkedHashMap<>();
    private final TransformContext lookups;

    public ConfigCatalog() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Resource r : resolver.getResources("classpath:berth9/schemas/*.json")) {
            Map<String, Object> json = read(r);
            TargetSchema schema = ConfigLoader.schema(json);
            schemas.put(schema.name(), schema);
            rawSchemas.put(schema.name(), json);
        }
        for (Resource r : resolver.getResources("classpath:berth9/rules/*.json")) {
            Map<String, Object> json = read(r);
            rules.put(String.valueOf(json.get("schema")), ConfigLoader.rules(json));
        }
        this.lookups = new TransformContext(ConfigLoader.lookups(read(resolver.getResource("classpath:berth9/lookups.json"))));
    }

    public TargetSchema schema(String name) {
        TargetSchema schema = schemas.get(name);
        if (schema == null) {
            throw new NoSuchElementException("unknown schema " + name);
        }
        return schema;
    }

    public Map<String, TargetSchema> schemas() {
        return schemas;
    }

    public Map<String, Object> rawSchema(String name) {
        return rawSchemas.get(name);
    }

    public List<Rule> rules(String schema) {
        return rules.getOrDefault(schema, List.of());
    }

    public TransformContext lookups() {
        return lookups;
    }

    public static Map<String, Object> read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
    }
}
