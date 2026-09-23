package dev.berth9.engine.map;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders keys like {@code {poNumber}|{sku}} from a record's values. */
public final class KeyTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private KeyTemplate() {
    }

    /** Returns null when any placeholder has no value, so callers can skip the lookup. */
    public static String render(String template, Map<String, Object> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = values.get(m.group(1));
            if (v == null || v.toString().isBlank()) {
                return null;
            }
            String text = v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : v.toString().strip();
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }
}
