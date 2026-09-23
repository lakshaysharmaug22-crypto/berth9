package dev.berth9.engine.rules;

import dev.berth9.engine.ref.ReferenceData;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-file validation state: reference data, "today" (injectable for tests and replays) and the keys
 * seen so far for in-file uniqueness checks. One context per file; not shared across threads.
 */
public final class ValidationContext {

    private final ReferenceData reference;
    private final LocalDate today;
    private final Map<String, Map<String, Long>> seen = new HashMap<>();

    public ValidationContext(ReferenceData reference, LocalDate today) {
        this.reference = reference == null ? ReferenceData.none() : reference;
        this.today = today == null ? LocalDate.now() : today;
    }

    public ReferenceData reference() {
        return reference;
    }

    public LocalDate today() {
        return today;
    }

    /** Registers {@code key} for {@code ruleId}; returns the line it was first seen on, or null if new. */
    Long firstSeen(String ruleId, String key, long line) {
        Map<String, Long> keys = seen.computeIfAbsent(ruleId, r -> new HashMap<>());
        Long previous = keys.get(key);
        if (previous == null) {
            keys.put(key, line);
        }
        return previous;
    }
}
