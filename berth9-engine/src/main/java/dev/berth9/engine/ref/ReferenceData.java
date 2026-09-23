package dev.berth9.engine.ref;

import java.util.Map;
import java.util.Optional;

/**
 * Master data the engine checks records against: open purchase orders, the product catalog,
 * FX rates, keys already delivered. The server backs this with SQL; tests use an in-memory map.
 */
@FunctionalInterface
public interface ReferenceData {

    Optional<Map<String, Object>> find(String dataset, String key);

    static ReferenceData none() {
        return (dataset, key) -> Optional.empty();
    }
}
