package dev.berth9.engine.rules;

import java.util.regex.Pattern;

/**
 * GSTIN validation: 2-digit state code, 10-character PAN, entity number, 'Z', and a check character
 * computed with a Luhn-style mod-36 algorithm over the first 14 characters.
 */
public final class Gstin {

    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern FORMAT = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    private Gstin() {
    }

    public static boolean isValid(String gstin) {
        if (gstin == null) {
            return false;
        }
        String g = gstin.strip().toUpperCase();
        return FORMAT.matcher(g).matches() && checkChar(g.substring(0, 14)) == g.charAt(14);
    }

    public static boolean hasValidFormat(String gstin) {
        return gstin != null && FORMAT.matcher(gstin.strip().toUpperCase()).matches();
    }

    /** Check character for the first 14 characters of a GSTIN. */
    public static char checkChar(String first14) {
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int value = CHARSET.indexOf(first14.charAt(i));
            int product = value * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARSET.charAt((36 - (sum % 36)) % 36);
    }
}
