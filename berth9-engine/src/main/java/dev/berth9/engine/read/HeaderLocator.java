package dev.berth9.engine.read;

import dev.berth9.engine.text.Texts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the real header in exported spreadsheets and CSVs, which rarely start on row 1:
 * company banners, "Period: Sep 2026" lines, blank rows and two-row merged headers
 * ("Tax (INR)" spanning "CGST | SGST | IGST") all come first.
 */
public final class HeaderLocator {

    static final int SCAN_DEPTH = 25;

    private static final Set<String> HEADER_WORDS = Set.of(
            "no", "number", "num", "date", "dt", "qty", "quantity", "amount", "amt", "rate", "price", "total",
            "invoice", "inv", "po", "order", "item", "sku", "code", "description", "desc", "uom", "unit", "tax",
            "gst", "gstin", "hsn", "line", "currency", "id", "name", "value", "part", "material", "sr", "sl");

    private HeaderLocator() {
    }

    /** Index of the best header row among {@code rows}, or -1 when nothing looks like a header. */
    public static int locate(List<List<String>> rows) {
        int best = -1;
        double bestScore = 0;
        Set<String> seenRows = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            String signature = String.join("|", rows.get(i).stream().map(Texts::normalizeKey).toList());
            if (!seenRows.add(signature)) {
                continue; // a header repeated at a page break is not a better header
            }
            double score = score(rows, i);
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    static double score(List<List<String>> rows, int index) {
        List<String> row = rows.get(index);
        int nonBlank = nonBlank(row);
        if (nonBlank < 2) {
            return 0;
        }
        int labels = 0;
        int keywordHits = 0;
        Set<String> distinct = new HashSet<>();
        for (String cell : row) {
            if (Texts.isBlank(cell)) {
                continue;
            }
            if (isLabel(cell)) {
                labels++;
            }
            distinct.add(Texts.normalizeKey(cell));
            for (String token : Texts.normalizeKey(cell).split(" ")) {
                if (HEADER_WORDS.contains(token)) {
                    keywordHits++;
                    break;
                }
            }
        }
        double labelRatio = labels / (double) nonBlank;
        if (labelRatio < 0.6) {
            return 0;
        }
        double uniqueness = distinct.size() / (double) nonBlank;
        int looked = 0;
        int similar = 0;
        int dataLike = 0;
        for (int j = index + 1; j < rows.size() && looked < 5; j++) {
            List<String> next = rows.get(j);
            int nb = nonBlank(next);
            if (nb == 0) {
                continue;
            }
            looked++;
            if (nb >= Math.max(2, 0.5 * nonBlank)) {
                similar++;
            }
            if (hasNumeric(next)) {
                dataLike++;
            }
        }
        double consistency = looked == 0 ? 0.3 : similar / (double) looked;
        double dataBelow = looked == 0 ? 0.3 : dataLike / (double) looked;
        return nonBlank * labelRatio * uniqueness * (0.4 + 0.6 * consistency) * (0.5 + 0.5 * dataBelow) + 0.75 * keywordHits;
    }

    /** True when every non-blank cell is a short text label and there is at least one. */
    public static boolean isHeaderLike(List<String> row) {
        int nonBlank = 0;
        for (String cell : row) {
            if (Texts.isBlank(cell)) {
                continue;
            }
            nonBlank++;
            if (!isLabel(cell)) {
                return false;
            }
        }
        return nonBlank > 0;
    }

    /**
     * A banner row above the header whose cells label groups of columns (a merged "Invoice" cell over
     * "No." and "Date"). Titles are excluded: they are long and usually sit alone in column A.
     */
    public static boolean isGroupRow(List<String> above, List<String> header) {
        if (nonBlank(above) < 2 || !isHeaderLike(above)) {
            return false;
        }
        for (String cell : above) {
            if (!Texts.isBlank(cell) && cell.strip().length() > 25) {
                return false;
            }
        }
        return hasSpanGap(above, header);
    }

    /**
     * The row right under a header that holds sub-labels of merged cells, e.g. "No. | Date"
     * under a merged "Invoice". Must be followed by data, otherwise it is just a second title.
     */
    public static boolean isSubHeader(List<String> header, List<String> candidate, List<String> firstDataRow) {
        return nonBlank(candidate) >= 2
                && isHeaderLike(candidate)
                && hasSpanGap(header, candidate)
                && firstDataRow != null
                && hasNumeric(firstDataRow);
    }

    private static boolean hasSpanGap(List<String> top, List<String> bottom) {
        for (int i = 0; i < Math.min(top.size(), bottom.size()); i++) {
            if (Texts.isBlank(cell(top, i)) && !Texts.isBlank(cell(bottom, i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds unique column names from a header row, optionally combined with a group row above it.
     * A group label carries right across the columns it was merged over.
     */
    public static List<String> columnNames(List<String> groupRow, List<String> headerRow) {
        int width = Math.max(headerRow.size(), groupRow == null ? 0 : groupRow.size());
        List<String> names = new ArrayList<>(width);
        String carried = null;
        for (int i = 0; i < width; i++) {
            String sub = Texts.cleanHeader(cell(headerRow, i));
            String name = sub;
            if (groupRow != null) {
                String group = Texts.cleanHeader(cell(groupRow, i));
                if (!group.isEmpty()) {
                    carried = group;
                } else if (sub.isEmpty()) {
                    carried = null;
                }
                String effective = group.isEmpty() ? carried : group;
                if (effective != null && !effective.isEmpty()) {
                    name = sub.isEmpty() ? effective : (sameLabel(effective, sub) ? sub : effective + " " + sub);
                }
            }
            names.add(name.isEmpty() ? "column_" + (i + 1) : name);
        }
        return dedupe(names);
    }

    static List<String> dedupe(List<String> names) {
        List<String> result = new ArrayList<>(names.size());
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            String candidate = name;
            int n = 2;
            while (!seen.add(candidate.toLowerCase(Locale.ROOT))) {
                candidate = name + " (" + n++ + ")";
            }
            result.add(candidate);
        }
        return result;
    }

    private static boolean sameLabel(String a, String b) {
        return Texts.normalizeKey(a).equals(Texts.normalizeKey(b));
    }

    static boolean isLabel(String cell) {
        String s = cell.strip();
        return s.length() <= 40
                && Texts.hasLetter(s)
                && Texts.digitRatio(s) <= 0.25
                && !Texts.looksNumeric(s)
                && !Texts.looksLikeDate(s);
    }

    static int nonBlank(List<String> row) {
        int n = 0;
        for (String cell : row) {
            if (!Texts.isBlank(cell)) {
                n++;
            }
        }
        return n;
    }

    private static boolean hasNumeric(List<String> row) {
        for (String cell : row) {
            if (!Texts.isBlank(cell) && (Texts.looksNumeric(cell) || Texts.looksLikeDate(cell))) {
                return true;
            }
        }
        return false;
    }

    private static String cell(List<String> row, int i) {
        return i < row.size() && row.get(i) != null ? row.get(i) : "";
    }
}
