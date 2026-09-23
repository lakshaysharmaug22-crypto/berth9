package dev.berth9.engine.read;

/**
 * One column of a fixed-width record layout, as a partner's legacy spec describes it.
 *
 * @param name   column name from the partner's layout document
 * @param start  1-based start position
 * @param length number of characters
 */
public record FixedColumn(String name, int start, int length) {

    public FixedColumn {
        if (start < 1 || length < 1) {
            throw new IllegalArgumentException("invalid fixed-width column " + name + ": start=" + start + " length=" + length);
        }
    }

    String slice(String line) {
        int from = start - 1;
        if (from >= line.length()) {
            return "";
        }
        return line.substring(from, Math.min(from + length, line.length())).strip();
    }
}
