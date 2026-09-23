package dev.berth9.engine.suggest;

import dev.berth9.engine.model.ValueKind;
import dev.berth9.engine.rules.Gstin;
import dev.berth9.engine.text.Texts;
import dev.berth9.engine.transform.Dates;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Guesses the shape of a single value. A value can have several kinds ("500" is INTEGER and AMOUNT). */
public final class ValueClassifier {

    static final Set<String> UOM_CODES = Set.of("EA", "EACH", "PCS", "PC", "PIECE", "PIECES", "NOS", "NO", "UNIT", "UNITS",
            "KG", "KGS", "KILOGRAM", "BOX", "BOXES", "BX", "CS", "CASE", "CASES", "CT", "CTN", "CARTON", "PK", "PACK", "PKT",
            "SET", "DZ", "DOZEN", "M", "MTR", "METER", "METRE", "RL", "ROLL", "ROLLS", "PR", "PAIR", "L", "LTR", "LB", "LBS",
            "BAG", "BAGS", "BG", "BD", "BUNDLE");
    static final Set<String> CURRENCIES = Set.of("USD", "INR", "EUR", "GBP", "$", "₹", "RS", "RS.", "US$");

    private static final Pattern INTEGER = Pattern.compile("^[+-]?\\d{1,12}$");
    private static final Pattern DECIMAL = Pattern.compile("^[+-]?\\d{1,3}(,\\d{2,3})*(\\.\\d+)?$|^[+-]?\\d+\\.\\d+$");
    private static final Pattern MONEY_MARK = Pattern.compile("[$₹€£]|(?i)\\b(rs\\.?|inr|usd)\\b|\\d\\s*(?i)(l|lakh|lakhs|cr|crore)$|/-$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 ()-]{8,16}$");
    private static final Pattern CODE = Pattern.compile("^(?=.*\\d)[A-Za-z0-9][A-Za-z0-9/_.#-]{2,30}$");
    private static final Pattern TEXT = Pattern.compile("^[A-Za-z][A-Za-z0-9 .,&()'/+-]*$");

    private ValueClassifier() {
    }

    public static Set<ValueKind> classify(String raw) {
        String s = Texts.clean(raw);
        if (s == null) {
            return EnumSet.of(ValueKind.BLANK);
        }
        EnumSet<ValueKind> kinds = EnumSet.noneOf(ValueKind.class);
        String upper = s.toUpperCase(Locale.ROOT);
        if (CURRENCIES.contains(upper)) {
            kinds.add(ValueKind.CURRENCY);
        }
        if (UOM_CODES.contains(upper)) {
            kinds.add(ValueKind.UOM);
        }
        if (Gstin.hasValidFormat(upper)) {
            kinds.add(ValueKind.GSTIN);
        }
        if (EMAIL.matcher(s).matches()) {
            kinds.add(ValueKind.EMAIL);
        }
        if (isDate(s)) {
            kinds.add(ValueKind.DATE);
        }
        boolean integer = INTEGER.matcher(s).matches();
        if (integer) {
            kinds.add(ValueKind.INTEGER);
            kinds.add(ValueKind.DECIMAL);
        } else if (DECIMAL.matcher(s).matches()) {
            kinds.add(ValueKind.DECIMAL);
            kinds.add(ValueKind.AMOUNT);
        }
        if (MONEY_MARK.matcher(s).find() && Texts.looksNumeric(s.replaceAll("(?i)\\s*(l|lakh|lakhs|cr|crore)$", ""))) {
            kinds.add(ValueKind.AMOUNT);
        }
        if (integer) {
            kinds.add(ValueKind.AMOUNT);
        }
        if (!integer && PHONE.matcher(s).matches() && s.replaceAll("\\D", "").length() >= 10 && !kinds.contains(ValueKind.DATE)) {
            kinds.add(ValueKind.PHONE);
        }
        if (!kinds.contains(ValueKind.DATE) && !kinds.contains(ValueKind.DECIMAL) && !kinds.contains(ValueKind.GSTIN)
                && CODE.matcher(s).matches()) {
            kinds.add(ValueKind.CODE);
        }
        if (kinds.isEmpty() && TEXT.matcher(s).matches()) {
            kinds.add(ValueKind.TEXT);
        }
        if (kinds.isEmpty()) {
            kinds.add(ValueKind.TEXT);
        }
        return kinds;
    }

    private static boolean isDate(String s) {
        if (!Texts.looksLikeDate(s) && !s.matches("^(19|20)\\d{6}$")) {
            return false;
        }
        return !Dates.inferPatterns(java.util.List.of(s)).isEmpty();
    }
}
