package dev.berth9.engine.text;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Small string helpers shared by readers, transforms and the mapping suggester. */
public final class Texts {

    private static final Set<String> NULL_TOKENS = Set.of("NA", "N/A", "N.A.", "NULL", "NIL", "-", "--", "---", "#N/A");
    private static final Pattern NUMERIC = Pattern.compile("^[(+-]?\\s*(?:[$₹€£]|RS\\.?|INR|USD)?\\s*[0-9][0-9,.'\\s]*\\)?(?:\\s*(?:CR|DR|-))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_SHAPE = Pattern.compile(
            "^(\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{1,2}[-\\s][A-Za-z]{3,9}[-\\s,]+\\d{2,4}|[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4}|\\d{4}-\\d{2}-\\d{2}T.*)$");

    private Texts() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Trims, replaces non-breaking spaces and turns the usual "no value" tokens into null. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace(' ', ' ').strip();
        if (s.isEmpty() || NULL_TOKENS.contains(s.toUpperCase(Locale.ROOT))) {
            return null;
        }
        return s;
    }

    public static boolean looksNumeric(String s) {
        return s != null && NUMERIC.matcher(s.strip()).matches();
    }

    public static boolean looksLikeDate(String s) {
        return s != null && DATE_SHAPE.matcher(s.strip()).matches();
    }

    public static double digitRatio(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        long digits = s.chars().filter(Character::isDigit).count();
        return digits / (double) s.length();
    }

    public static boolean hasLetter(String s) {
        return s != null && s.chars().anyMatch(Character::isLetter);
    }

    /** Collapses whitespace and trims trailing punctuation from a header cell: "Invoice  No.:" becomes "Invoice No". */
    public static String cleanHeader(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace(' ', ' ').replaceAll("\\s+", " ").strip();
        while (!s.isEmpty() && ".:".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    /** Lower-case alphanumeric tokens joined by single spaces; splits camelCase and XML paths. */
    public static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9#₹$%]+", " ")
                .strip();
        return s.replaceAll("\\s+", " ");
    }
}
