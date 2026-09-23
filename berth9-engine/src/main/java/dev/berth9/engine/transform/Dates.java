package dev.berth9.engine.transform;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Date parsing for partner files. Patterns are applied strictly (31/02 is rejected, not rolled over),
 * Excel serial numbers and ISO timestamps are understood, and {@link #inferPatterns} works out which
 * patterns fit a column so an ambiguous 03/04/2026 can be settled by the other rows.
 */
public final class Dates {

    /** Candidate patterns, most specific first. Both day-first and month-first orders are present on purpose. */
    public static final List<String> CANDIDATES = List.of(
            "yyyy-MM-dd", "yyyyMMdd", "yyyy/MM/dd",
            "dd-MM-yyyy", "dd/MM/yyyy", "dd.MM.yyyy",
            "MM/dd/yyyy", "MM-dd-yyyy",
            "d/M/yyyy", "M/d/yyyy",
            "dd-MMM-yyyy", "d-MMM-yyyy", "dd-MMM-yy", "d-MMM-yy", "dd MMM yyyy", "d MMM yyyy",
            "MMM d, yyyy", "MMM dd, yyyy", "MMMM d, yyyy",
            "dd/MM/yy", "MM/dd/yy", "yyMMdd");

    /** Patterns safe to try without partner context (no day/month ambiguity). */
    static final List<String> UNAMBIGUOUS = List.of(
            "yyyy-MM-dd", "yyyyMMdd", "yyyy/MM/dd", "dd-MMM-yyyy", "d-MMM-yyyy", "dd-MMM-yy", "d-MMM-yy",
            "dd MMM yyyy", "d MMM yyyy", "MMM d, yyyy", "MMM dd, yyyy", "MMMM d, yyyy");

    private static final Pattern EXCEL_SERIAL = Pattern.compile("^\\d{5}(\\.\\d+)?$");
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final Map<String, DateTimeFormatter> CACHE = new ConcurrentHashMap<>();

    private Dates() {
    }

    public static LocalDate parse(String raw, List<String> patterns) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        LocalDate special = parseSpecial(s);
        if (special != null) {
            return special;
        }
        for (String pattern : patterns) {
            LocalDate d = tryParse(s, pattern);
            if (d != null) {
                return d;
            }
        }
        throw new TransformException("'" + s + "' does not match date format " + String.join(" or ", patterns));
    }

    /** Parses only when the text cannot be misread (ISO, month names, Excel serials). */
    public static LocalDate parseUnambiguous(String raw) {
        return parse(raw, UNAMBIGUOUS);
    }

    public static LocalDate tryParse(String s, String pattern) {
        try {
            return LocalDate.parse(s.strip(), formatter(pattern));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Candidate patterns that parse every non-blank sample. */
    public static List<String> inferPatterns(List<String> samples) {
        List<String> values = samples.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
        List<String> fits = new ArrayList<>();
        if (values.isEmpty()) {
            return fits;
        }
        if (values.stream().allMatch(v -> parseSpecial(v) != null)) {
            fits.add("iso");
            return fits;
        }
        for (String pattern : CANDIDATES) {
            if (values.stream().allMatch(v -> tryParse(v, pattern) != null)) {
                fits.add(pattern);
            }
        }
        return fits;
    }

    private static LocalDate parseSpecial(String s) {
        if (s.length() > 10 && s.charAt(4) == '-' && s.contains("T")) {
            try {
                return OffsetDateTime.parse(s).toLocalDate();
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDateTime.parse(s).toLocalDate();
                } catch (DateTimeParseException ignoredToo) {
                    return null;
                }
            }
        }
        if (EXCEL_SERIAL.matcher(s).matches()) {
            double serial = Double.parseDouble(s);
            if (serial > 20000 && serial < 80000) {
                return EXCEL_EPOCH.plusDays((long) serial);
            }
        }
        return null;
    }

    static DateTimeFormatter formatter(String pattern) {
        return CACHE.computeIfAbsent(pattern, p -> new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(p.replace("yyyy", "uuuu").replace("yy", "uu"))
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT));
    }
}
