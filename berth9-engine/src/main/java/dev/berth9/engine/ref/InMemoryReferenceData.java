package dev.berth9.engine.ref;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory reference data, used by tests, the CLI and the demo profile. */
public final class InMemoryReferenceData implements ReferenceData {

    private final Map<String, Map<String, Map<String, Object>>> datasets = new ConcurrentHashMap<>();

    public InMemoryReferenceData put(String dataset, String key, Map<String, Object> row) {
        datasets.computeIfAbsent(dataset, d -> new ConcurrentHashMap<>()).put(normalize(key), new HashMap<>(row));
        return this;
    }

    public int size(String dataset) {
        return datasets.getOrDefault(dataset, Map.of()).size();
    }

    @Override
    public Optional<Map<String, Object>> find(String dataset, String key) {
        Map<String, Map<String, Object>> rows = datasets.get(dataset);
        if (rows == null || key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rows.get(normalize(key)));
    }

    private static String normalize(String key) {
        return key.strip().toUpperCase(Locale.ROOT);
    }
}
