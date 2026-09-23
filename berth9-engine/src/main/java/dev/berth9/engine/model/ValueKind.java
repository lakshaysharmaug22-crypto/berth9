package dev.berth9.engine.model;

/**
 * Semantic shape of a value, inferred from its text. Used to profile partner columns
 * and to check whether a column's contents fit a target field.
 */
public enum ValueKind {
    BLANK,
    DATE,
    AMOUNT,
    INTEGER,
    DECIMAL,
    CURRENCY,
    UOM,
    GSTIN,
    CODE,
    TEXT,
    EMAIL,
    PHONE
}
