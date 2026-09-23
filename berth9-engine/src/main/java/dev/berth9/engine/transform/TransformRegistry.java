package dev.berth9.engine.transform;

import dev.berth9.engine.text.Texts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Named transforms usable in mapping specs. New ones are registered at startup, so adding a
 * partner-specific quirk never means touching the mapper (open/closed).
 */
public final class TransformRegistry {

    private static final Set<String> SMALL_WORDS = Set.of("a", "an", "and", "of", "the", "for", "in", "on", "to", "x");
    private static final Map<String, String> CURRENCY_ALIASES = Map.ofEntries(
            Map.entry("₹", "INR"), Map.entry("RS", "INR"), Map.entry("RS.", "INR"), Map.entry("INR", "INR"), Map.entry("RUPEES", "INR"),
            Map.entry("$", "USD"), Map.entry("US$", "USD"), Map.entry("USD", "USD"), Map.entry("DOLLARS", "USD"),
            Map.entry("€", "EUR"), Map.entry("EUR", "EUR"), Map.entry("£", "GBP"), Map.entry("GBP", "GBP"));

    private final Map<String, Transform> transforms = new TreeMap<>();

    public static TransformRegistry standard() {
        TransformRegistry r = new TransformRegistry();
        r.register("trim", (v, a, c) -> v == null ? null : str(v).strip());
        r.register("upper", (v, a, c) -> v == null ? null : str(v).toUpperCase(Locale.ROOT));
        r.register("lower", (v, a, c) -> v == null ? null : str(v).toLowerCase(Locale.ROOT));
        r.register("collapse", (v, a, c) -> v == null ? null : str(v).replaceAll("\\s+", " ").strip());
        r.register("title", (v, a, c) -> v == null ? null : titleCase(str(v)));
        r.register("digits", (v, a, c) -> v == null ? null : str(v).replaceAll("\\D", ""));
        r.register("alnum", (v, a, c) -> v == null ? null : str(v).replaceAll("[^A-Za-z0-9]", ""));
        r.register("nullif", (v, a, c) -> v != null && a.stream().anyMatch(x -> x.equalsIgnoreCase(str(v).strip())) ? null : v);
        r.register("default", (v, a, c) -> v == null || str(v).isBlank() ? arg(a, 0, "default") : v);
        r.register("prefix", (v, a, c) -> v == null ? null : arg(a, 0, "prefix") + str(v));
        r.register("suffix", (v, a, c) -> v == null ? null : str(v) + arg(a, 0, "suffix"));
        r.register("replace", (v, a, c) -> v == null ? null : str(v).replaceAll(arg(a, 0, "replace"), a.size() > 1 ? a.get(1) : ""));
        r.register("extract", TransformRegistry::extract);
        r.register("left", (v, a, c) -> v == null ? null : left(str(v), intArg(a, 0)));
        r.register("right", (v, a, c) -> v == null ? null : right(str(v), intArg(a, 0)));
        r.register("split", TransformRegistry::split);
        r.register("amount", (v, a, c) -> v == null ? null : v instanceof BigDecimal b ? b : Amounts.parse(str(v)));
        r.register("number", (v, a, c) -> v == null ? null : v instanceof BigDecimal b ? b : Amounts.parse(str(v)));
        r.register("round", (v, a, c) -> v == null ? null : decimal(v).setScale(intArg(a, 0), RoundingMode.HALF_UP));
        r.register("mul", (v, a, c) -> v == null ? null : decimal(v).multiply(new BigDecimal(arg(a, 0, "mul"))));
        r.register("div", (v, a, c) -> v == null ? null : decimal(v).divide(new BigDecimal(arg(a, 0, "div")), 6, RoundingMode.HALF_UP).stripTrailingZeros());
        r.register("implied", (v, a, c) -> v == null ? null : new BigDecimal(str(v).strip().replaceFirst("^\\+", "")).movePointLeft(intArg(a, 0)));
        r.register("date", (v, a, c) -> v == null ? null : v instanceof LocalDate d ? d
                : Dates.parse(str(v), a.isEmpty() ? Dates.UNAMBIGUOUS : a));
        r.register("currency", (v, a, c) -> v == null ? null : currency(str(v)));
        r.register("lookup", TransformRegistry::lookup);
        r.register("bool", (v, a, c) -> v == null ? null : bool(str(v)));
        return r;
    }

    public TransformRegistry register(String name, Transform transform) {
        transforms.put(name.toLowerCase(Locale.ROOT), transform);
        return this;
    }

    public Transform get(String name) {
        Transform t = transforms.get(name.toLowerCase(Locale.ROOT));
        if (t == null) {
            throw new IllegalArgumentException("unknown transform '" + name + "'. Available: " + String.join(", ", transforms.keySet()));
        }
        return t;
    }

    public Set<String> names() {
        return transforms.keySet();
    }

    private static Object extract(Object v, List<String> args, TransformContext ctx) {
        if (v == null) {
            return null;
        }
        Matcher m = Pattern.compile(arg(args, 0, "extract")).matcher(str(v));
        if (!m.find()) {
            return null;
        }
        int group = args.size() > 1 ? Integer.parseInt(args.get(1).strip()) : (m.groupCount() >= 1 ? 1 : 0);
        return m.group(group);
    }

    private static Object split(Object v, List<String> args, TransformContext ctx) {
        if (v == null) {
            return null;
        }
        String[] parts = str(v).split(Pattern.quote(arg(args, 0, "split")), -1);
        int index = args.size() > 1 ? Integer.parseInt(args.get(1).strip()) : 0;
        if (index < 0) {
            index = parts.length + index;
        }
        return index >= 0 && index < parts.length ? parts[index].strip() : null;
    }

    private static Object lookup(Object v, List<String> args, TransformContext ctx) {
        if (v == null) {
            return null;
        }
        String table = arg(args, 0, "lookup");
        String hit = ctx.lookup(table, str(v));
        if (hit != null) {
            return hit;
        }
        if (args.size() > 1) {
            return args.get(1).equals("*") ? v : args.get(1);
        }
        throw new TransformException("'" + str(v) + "' is not a known value in '" + table + "'");
    }

    static String currency(String raw) {
        String key = raw.strip().toUpperCase(Locale.ROOT);
        String code = CURRENCY_ALIASES.get(key);
        if (code != null) {
            return code;
        }
        if (key.matches("[A-Z]{3}")) {
            return key;
        }
        throw new TransformException("'" + raw + "' is not a recognised currency");
    }

    static Boolean bool(String raw) {
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "Y", "YES", "TRUE", "T", "1" -> Boolean.TRUE;
            case "N", "NO", "FALSE", "F", "0" -> Boolean.FALSE;
            default -> throw new TransformException("'" + raw + "' is not a yes/no value");
        };
    }

    static String titleCase(String s) {
        String[] words = s.strip().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            boolean keepLower = i > 0 && SMALL_WORDS.contains(w);
            boolean looksLikeCode = w.chars().anyMatch(Character::isDigit);
            if (keepLower) {
                out.append(w);
            } else if (looksLikeCode) {
                out.append(w.toUpperCase(Locale.ROOT));
            } else {
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return out.toString();
    }

    static BigDecimal decimal(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return Amounts.parse(str(v));
    }

    static String str(Object v) {
        return v instanceof BigDecimal b ? b.toPlainString() : String.valueOf(v);
    }

    private static String arg(List<String> args, int index, String fn) {
        if (args.size() <= index) {
            throw new IllegalArgumentException("transform '" + fn + "' needs argument " + (index + 1));
        }
        return args.get(index);
    }

    private static int intArg(List<String> args, int index) {
        if (args.size() <= index) {
            throw new IllegalArgumentException("missing numeric argument");
        }
        return Integer.parseInt(args.get(index).strip());
    }

    private static String left(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String right(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    static boolean isBlank(Object v) {
        return v == null || Texts.isBlank(str(v));
    }
}
