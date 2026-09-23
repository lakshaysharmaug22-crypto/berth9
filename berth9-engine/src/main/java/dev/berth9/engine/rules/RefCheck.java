package dev.berth9.engine.rules;

import dev.berth9.engine.model.Severity;

import java.math.BigDecimal;

/** Comparisons between a record and the reference row it matched. */
public sealed interface RefCheck {

    /** Short name of the reference value used in messages, e.g. "PO price". */
    String label();

    /** Values must be equal (case-insensitive), e.g. invoice currency = PO currency. */
    record Equals(String field, String refField, String label, Severity severity) implements RefCheck {
    }

    /**
     * Percentage deviation from the reference value: above {@code warnPct} is a WARNING, above
     * {@code errorPct} an ERROR. Classic price-variance check against the PO.
     */
    record WithinPct(String field, String refField, String label, BigDecimal warnPct, BigDecimal errorPct) implements RefCheck {
    }

    /**
     * Value must not exceed {@code refField - minusRefField} plus a tolerance, e.g. invoiced quantity must
     * not exceed ordered quantity minus what was already invoiced (+5% over-shipment tolerance).
     */
    record AtMost(String field, String refField, String minusRefField, String label, BigDecimal tolerancePct, Severity severity) implements RefCheck {
    }
}
