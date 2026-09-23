package dev.berth9.engine.map;

import java.util.Map;

/**
 * Content-enricher steps applied after mapping and before validation.
 */
public sealed interface Enrichment {

    /**
     * Copies fields from a reference dataset row, e.g. fill a missing description or UOM from the catalog.
     *
     * @param dataset     reference dataset name
     * @param key         key template such as {@code {sku}}
     * @param copy        target field to reference column
     * @param onlyIfBlank only fill fields the partner left empty
     */
    record Lookup(String dataset, String key, Map<String, String> copy, boolean onlyIfBlank) implements Enrichment {
        public Lookup {
            copy = Map.copyOf(copy);
        }
    }

    /**
     * Converts an amount to a base currency using the {@code fx} dataset (rate per unit of foreign currency).
     */
    record Convert(String amountField, String currencyField, String targetField, String baseCurrency) implements Enrichment {
    }

    /**
     * Derives a field from two others when the partner did not send it, e.g. {@code lineAmount = quantity * unitPrice}.
     */
    record Compute(String targetField, String left, String operator, String right, int scale) implements Enrichment {
    }
}
