package dev.berth9.engine.model;

/**
 * ERROR sends a record to the exceptions queue; WARNING lets it through with a flag.
 */
public enum Severity {
    ERROR,
    WARNING
}
