package dev.berth9.engine.read;

import java.util.List;

/**
 * A raw row of cells from a tabular source (CSV or spreadsheet), before headers are known.
 *
 * @param number 1-based physical row number
 * @param cells  cell texts in column order
 */
public record Row(long number, List<String> cells) {

    public Row {
        cells = List.copyOf(cells);
    }

    public boolean isBlank() {
        return cells.stream().allMatch(c -> c == null || c.isBlank());
    }
}
