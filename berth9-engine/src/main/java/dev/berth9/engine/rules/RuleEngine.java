package dev.berth9.engine.rules;

import dev.berth9.engine.map.KeyTemplate;
import dev.berth9.engine.map.MappedRecord;
import dev.berth9.engine.model.Severity;
import dev.berth9.engine.model.Violation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Evaluates rules against mapped records. Stateless apart from a compiled-regex cache; safe to share. */
public final class RuleEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    public List<Violation> evaluate(MappedRecord record, List<Rule> rules, ValidationContext ctx) {
        List<Violation> out = new ArrayList<>(record.issues());
        for (Rule rule : rules) {
            check(rule, record, ctx, out);
        }
        return out;
    }

    private void check(Rule rule, MappedRecord r, ValidationContext ctx, List<Violation> out) {
        switch (rule) {
            case Rule.Required req -> {
                if (blank(r.get(req.field()))) {
                    out.add(v(req, req.field(), req.field() + " is missing"));
                }
            }
            case Rule.Matches m -> {
                Object value = r.get(m.field());
                if (!blank(value) && !pattern(m.regex()).matcher(text(value)).matches()) {
                    out.add(v(m, m.field(), m.message() != null ? m.message() + " (got '" + text(value) + "')"
                            : m.field() + " '" + text(value) + "' has an invalid format"));
                }
            }
            case Rule.Range range -> {
                BigDecimal n = decimal(r.get(range.field()));
                if (n != null && ((range.min() != null && n.compareTo(range.min()) < 0)
                        || (range.max() != null && n.compareTo(range.max()) > 0))) {
                    out.add(v(range, range.field(), range.field() + " " + plain(n) + " is outside "
                            + bound(range.min()) + " to " + bound(range.max())));
                }
            }
            case Rule.DateWindow w -> {
                Object value = r.get(w.field());
                if (value instanceof LocalDate d) {
                    if (w.maxFutureDays() != null && d.isAfter(ctx.today().plusDays(w.maxFutureDays()))) {
                        out.add(v(w, w.field(), w.field() + " " + d + " is in the future"));
                    } else if (w.maxPastDays() != null && d.isBefore(ctx.today().minusDays(w.maxPastDays()))) {
                        out.add(v(w, w.field(), w.field() + " " + d + " is more than " + w.maxPastDays() + " days old"));
                    }
                }
            }
            case Rule.OneOf o -> {
                Object value = r.get(o.field());
                if (!blank(value) && o.allowed().stream().noneMatch(a -> a.equalsIgnoreCase(text(value)))) {
                    out.add(v(o, o.field(), o.field() + " '" + text(value) + "' is not one of " + String.join(", ", o.allowed())));
                }
            }
            case Rule.UniqueInFile u -> {
                StringJoiner key = new StringJoiner("|");
                for (String f : u.fields()) {
                    Object value = r.get(f);
                    if (blank(value)) {
                        return;
                    }
                    key.add(text(value).toUpperCase());
                }
                Long first = ctx.firstSeen(u.id(), key.toString(), r.line());
                if (first != null) {
                    out.add(v(u, u.fields().get(0), "duplicate of line " + first + " (" + String.join(" + ", u.fields()) + ")"));
                }
            }
            case Rule.Arithmetic a -> arithmetic(a, r, out);
            case Rule.Gstin g -> {
                Object value = r.get(g.field());
                if (!blank(value) && !Gstin.isValid(text(value))) {
                    String reason = Gstin.hasValidFormat(text(value)) ? "check digit does not match" : "wrong format";
                    out.add(v(g, g.field(), "GSTIN '" + text(value) + "' is invalid (" + reason + ")"));
                }
            }
            case Rule.Reference ref -> reference(ref, r, ctx, out);
            case Rule.When w -> {
                Object value = r.get(w.field());
                if (!blank(value) && w.equalsAny().stream().anyMatch(x -> x.equalsIgnoreCase(text(value)))) {
                    for (Rule nested : w.then()) {
                        check(nested, r, ctx, out);
                    }
                }
            }
        }
    }

    private void arithmetic(Rule.Arithmetic a, MappedRecord r, List<Violation> out) {
        BigDecimal actual = decimal(r.get(a.field()));
        BigDecimal left = decimal(r.get(a.left()));
        BigDecimal right = decimal(r.get(a.right()));
        if (actual == null || left == null || right == null) {
            return;
        }
        BigDecimal expected = switch (a.operator()) {
            case "*" -> left.multiply(right);
            case "+" -> left.add(right);
            case "-" -> left.subtract(right);
            default -> throw new IllegalArgumentException("unsupported operator " + a.operator());
        };
        BigDecimal allowed = a.tolerance() == null ? BigDecimal.ZERO : a.tolerance();
        if (a.tolerancePct() != null) {
            allowed = allowed.max(expected.abs().multiply(a.tolerancePct()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        }
        if (actual.subtract(expected).abs().compareTo(allowed) > 0) {
            out.add(v(a, a.field(), a.field() + " " + plain(actual) + " does not equal " + a.left() + " " + a.operator() + " "
                    + a.right() + " = " + plain(expected.setScale(2, RoundingMode.HALF_UP))));
        }
    }

    private void reference(Rule.Reference ref, MappedRecord r, ValidationContext ctx, List<Violation> out) {
        String key = KeyTemplate.render(ref.key(), r.values());
        if (key == null) {
            return;
        }
        Optional<Map<String, Object>> row = ctx.reference().find(ref.dataset(), key);
        if (ref.mustExist() && row.isEmpty()) {
            out.add(v(ref, firstField(ref.key()), ref.message() != null ? ref.message() + " (" + key + ")" : key + " not found in " + ref.dataset()));
            return;
        }
        if (!ref.mustExist() && row.isPresent()) {
            out.add(v(ref, firstField(ref.key()), ref.message() != null ? ref.message() + " (" + key + ")" : key + " already exists in " + ref.dataset()));
            return;
        }
        if (row.isEmpty()) {
            return;
        }
        Map<String, Object> refRow = row.get();
        for (RefCheck check : ref.checks()) {
            switch (check) {
                case RefCheck.Equals eq -> {
                    Object mine = r.get(eq.field());
                    Object theirs = refRow.get(eq.refField());
                    if (!blank(mine) && theirs != null && !text(mine).equalsIgnoreCase(text(theirs))) {
                        out.add(new Violation(ref.id(), eq.field(), eq.severity(),
                                eq.field() + " '" + text(mine) + "' does not match " + label(eq.label(), ref, eq.refField()) + " '" + text(theirs) + "'"));
                    }
                }
                case RefCheck.WithinPct pct -> {
                    BigDecimal mine = decimal(r.get(pct.field()));
                    BigDecimal theirs = decimal(refRow.get(pct.refField()));
                    if (mine == null || theirs == null || theirs.signum() == 0) {
                        continue;
                    }
                    BigDecimal deviation = mine.subtract(theirs).abs().multiply(HUNDRED).divide(theirs.abs(), 2, RoundingMode.HALF_UP);
                    Severity severity = pct.errorPct() != null && deviation.compareTo(pct.errorPct()) > 0 ? Severity.ERROR
                            : pct.warnPct() != null && deviation.compareTo(pct.warnPct()) > 0 ? Severity.WARNING : null;
                    if (severity != null) {
                        String direction = mine.compareTo(theirs) > 0 ? "above" : "below";
                        out.add(new Violation(ref.id(), pct.field(), severity, pct.field() + " " + plain(mine) + " is "
                                + plain(deviation) + "% " + direction + " " + label(pct.label(), ref, pct.refField()) + " " + plain(theirs)));
                    }
                }
                case RefCheck.AtMost most -> {
                    BigDecimal mine = decimal(r.get(most.field()));
                    BigDecimal limit = decimal(refRow.get(most.refField()));
                    if (mine == null || limit == null) {
                        continue;
                    }
                    BigDecimal already = most.minusRefField() == null ? BigDecimal.ZERO
                            : Optional.ofNullable(decimal(refRow.get(most.minusRefField()))).orElse(BigDecimal.ZERO);
                    BigDecimal open = limit.subtract(already);
                    BigDecimal withTolerance = most.tolerancePct() == null ? open
                            : open.multiply(HUNDRED.add(most.tolerancePct())).divide(HUNDRED, 6, RoundingMode.HALF_UP);
                    if (mine.compareTo(withTolerance) > 0) {
                        out.add(new Violation(ref.id(), most.field(), most.severity(), most.field() + " " + plain(mine)
                                + " exceeds " + label(most.label(), ref, "open quantity") + " " + plain(open)
                                + (most.tolerancePct() == null ? "" : " (+" + plain(most.tolerancePct()) + "% tolerance)")));
                    }
                }
            }
        }
    }

    private static String label(String label, Rule.Reference ref, String refField) {
        return label != null && !label.isBlank() ? label : ref.dataset() + " " + refField;
    }

    private Pattern pattern(String regex) {
        return patterns.computeIfAbsent(regex, Pattern::compile);
    }

    private static Violation v(Rule rule, String field, String message) {
        return new Violation(rule.id(), field, rule.severity(), message);
    }

    private static String firstField(String template) {
        int open = template.indexOf('{');
        int close = template.indexOf('}');
        return open >= 0 && close > open ? template.substring(open + 1, close) : null;
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static String text(Object v) {
        return v instanceof BigDecimal b ? plain(b) : String.valueOf(v).strip();
    }

    private static String plain(BigDecimal b) {
        return b.stripTrailingZeros().toPlainString();
    }

    private static String bound(BigDecimal b) {
        return b == null ? "∞" : plain(b);
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
