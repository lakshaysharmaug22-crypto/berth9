package dev.berth9.engine.pipeline;

import dev.berth9.engine.map.MappedRecord;
import dev.berth9.engine.model.SourceRecord;
import dev.berth9.engine.model.Violation;

import java.util.List;

/** Everything known about one record after it went through map, enrich and validate. */
public record RecordOutcome(SourceRecord source, MappedRecord mapped, List<Violation> violations, RecordStatus status) {

    public RecordOutcome {
        violations = List.copyOf(violations);
    }
}
