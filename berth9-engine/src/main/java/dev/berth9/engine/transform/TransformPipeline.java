package dev.berth9.engine.transform;

import java.util.ArrayList;
import java.util.List;

/**
 * A compiled transform expression such as {@code trim | upper | lookup(uom)} or
 * {@code replace('[^0-9.]', '') | amount}. Compiled once per mapping spec, applied per record.
 *
 * <p>Grammar: {@code step ('|' step)*}, {@code step := name ['(' arg (',' arg)* ')']}; arguments
 * may be single- or double-quoted to contain commas, pipes or parentheses.
 */
public final class TransformPipeline {

    private record Step(String name, List<String> args, Transform fn) {
    }

    private final String expression;
    private final List<Step> steps;

    private TransformPipeline(String expression, List<Step> steps) {
        this.expression = expression;
        this.steps = List.copyOf(steps);
    }

    public static TransformPipeline compile(String expression, TransformRegistry registry) {
        List<Step> steps = new ArrayList<>();
        if (expression != null && !expression.isBlank()) {
            for (String raw : splitTopLevel(expression, '|')) {
                String part = raw.strip();
                if (part.isEmpty()) {
                    continue;
                }
                int open = part.indexOf('(');
                String name;
                List<String> args = new ArrayList<>();
                if (open < 0) {
                    name = part;
                } else {
                    if (!part.endsWith(")")) {
                        throw new IllegalArgumentException("unbalanced parentheses in transform '" + part + "'");
                    }
                    name = part.substring(0, open).strip();
                    String inside = part.substring(open + 1, part.length() - 1);
                    if (!inside.isBlank()) {
                        for (String a : splitTopLevel(inside, ',')) {
                            args.add(unquote(a.strip()));
                        }
                    }
                }
                steps.add(new Step(name, args, registry.get(name)));
            }
        }
        return new TransformPipeline(expression == null ? "" : expression, steps);
    }

    public Object apply(Object value, TransformContext context) {
        Object v = value;
        for (Step step : steps) {
            v = step.fn().apply(v, step.args(), context);
        }
        return v;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public String expression() {
        return expression;
    }

    static List<String> splitTopLevel(String s, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                current.append(ch);
                if (ch == '\\' && i + 1 < s.length()) {
                    current.append(s.charAt(++i));
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                current.append(ch);
            } else if (ch == '(') {
                depth++;
                current.append(ch);
            } else if (ch == ')') {
                depth--;
                current.append(ch);
            } else if (ch == separator && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"') && s.charAt(s.length() - 1) == s.charAt(0)) {
            String inner = s.substring(1, s.length() - 1);
            return inner.replace("\\" + s.charAt(0), String.valueOf(s.charAt(0)));
        }
        return s;
    }
}
