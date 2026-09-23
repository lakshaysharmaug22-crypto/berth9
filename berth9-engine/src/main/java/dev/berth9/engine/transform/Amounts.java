package dev.berth9.engine.transform;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses money the way suppliers actually write it, in both US and Indian conventions:
 * {@code $1,234.50}, {@code ₹1,23,456.00}, {@code Rs. 4,500/-}, {@code 12.5 L} (lakh),
 * {@code 1.2 Cr} (crore), {@code 3.4K}, {@code (1,200.00)} and {@code 1,200.00 CR} (credits),
 * {@code 1.234,56} (European decimal comma).
 */
public final class Amounts {

    private static final Pattern CURRENCY_PREFIX = Pattern.compile("^(US\\$|\\$|₹|RS\\.?|INR|USD|EUR|€|£|GBP)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY_SUFFIX = Pattern.compile("\\s*(INR|USD|EUR|GBP|/-)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCALE_SUFFIX = Pattern.compile(
            "^(\\d{1,3}(?:\\.\\d+)?)\\s*(L|LAC|LACS|LAKH|LAKHS|CR|CRORE|CRORES|K|M|MN|MILLION)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN = Pattern.compile("^\\d+(\\.\\d+)?$");

    private Amounts() {
    }

    public static BigDecimal parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace('\u00A0', ' ').strip();
        if (s.isEmpty()) {
            return null;
        }
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.startsWith("-")) {
            negative = !negative;
            s = s.substring(1).strip();
        } else if (s.startsWith("+")) {
            s = s.substring(1).strip();
        }
        if (s.endsWith("-") && !s.endsWith("/-")) {
            negative = !negative;
            s = s.substring(0, s.length() - 1).strip();
        }
        s = CURRENCY_PREFIX.matcher(s).replaceFirst("");
        s = CURRENCY_SUFFIX.matcher(s).replaceFirst("").strip();
        s = CURRENCY_PREFIX.matcher(s).replaceFirst("").strip();

        Matcher scaled = SCALE_SUFFIX.matcher(s);
        if (scaled.matches()) {
            BigDecimal base = new BigDecimal(scaled.group(1));
            BigDecimal factor = switch (scaled.group(2).toUpperCase(Locale.ROOT)) {
                case "L", "LAC", "LACS", "LAKH", "LAKHS" -> new BigDecimal("100000");
                case "CR", "CRORE", "CRORES" -> new BigDecimal("10000000");
                case "K" -> new BigDecimal("1000");
                default -> new BigDecimal("1000000");
            };
            BigDecimal value = base.multiply(factor);
            return negative ? value.negate() : value;
        }
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.endsWith("CR")) {
            negative = !negative;
            s = s.substring(0, s.length() - 2).strip();
        } else if (upper.endsWith("DR")) {
            s = s.substring(0, s.length() - 2).strip();
        }
        s = s.replace(" ", "").replace("_", "").replace("'", "");
        s = normalizeSeparators(s);
        if (!PLAIN.matcher(s).matches()) {
            throw new TransformException("'" + raw.strip() + "' is not a valid amount");
        }
        BigDecimal value = new BigDecimal(s);
        return negative ? value.negate() : value;
    }

    /** Resolves thousands separators vs decimal separators for US, Indian and European styles. */
    static String normalizeSeparators(String s) {
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                return s.replace(".", "").replace(',', '.');
            }
            return s.replace(",", "");
        }
        if (lastComma >= 0) {
            int commas = s.length() - s.replace(",", "").length();
            int digitsAfter = s.length() - lastComma - 1;
            if (commas == 1 && digitsAfter >= 1 && digitsAfter <= 2) {
                return s.replace(',', '.');
            }
            return s.replace(",", "");
        }
        return s;
    }
}
