package dev.berth9.engine.pipeline;

/** Outcome of one record: VALID and WARNING records are delivered, ERROR records wait in exceptions. */
public enum RecordStatus {
    VALID,
    WARNING,
    ERROR
}
