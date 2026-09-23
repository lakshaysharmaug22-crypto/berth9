package dev.berth9.engine.config;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for engine configuration (schemas, mapping specs, rule sets).
 * Keeps the engine free of third-party dependencies so it can be embedded anywhere (a CLI, a
 * batch job, a serverless function). Numbers are read as BigDecimal to avoid float surprises.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.value();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw p.error("unexpected trailing content");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0, false);
        return out.toString();
    }

    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0, true);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int indent, boolean pretty) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            quote(s, out);
        } else if (value instanceof BigDecimal b) {
            out.append(b.toPlainString());
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Enum<?> e) {
            quote(e.name(), out);
        } else if (value instanceof TemporalAccessor || value instanceof Character) {
            quote(value.toString(), out);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            boolean first = true;
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, indent + 1, pretty);
                quote(String.valueOf(e.getKey()), out);
                out.append(pretty ? ": " : ":");
                write(e.getValue(), out, indent + 1, pretty);
            }
            if (!first) {
                newline(out, indent, pretty);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, indent + 1, pretty);
                write(item, out, indent + 1, pretty);
            }
            if (!first) {
                newline(out, indent, pretty);
            }
            out.append(']');
        } else {
            quote(value.toString(), out);
        }
    }

    private static void newline(StringBuilder out, int indent, boolean pretty) {
        if (pretty) {
            out.append('\n').append("  ".repeat(indent));
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            skipWhitespace();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error("expected a quoted key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value());
                skipWhitespace();
                if (peek(',')) {
                    i++;
                } else if (peek('}')) {
                    i++;
                    return map;
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++;
            skipWhitespace();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value());
                skipWhitespace();
                if (peek(',')) {
                    i++;
                } else if (peek(']')) {
                    i++;
                    return list;
                } else {
                    throw error("expected ',' or ']'");
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        break;
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw error("bad escape \\" + e);
                    }
                } else {
                    out.append(c);
                }
            }
            throw error("unterminated string");
        }

        BigDecimal number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw error("unexpected character '" + s.charAt(i) + "'");
            }
            try {
                return new BigDecimal(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw error("bad number " + s.substring(start, i));
            }
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) {
                throw error("unexpected token");
            }
            i += word.length();
            return value;
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            i++;
        }

        IllegalArgumentException error(String message) {
            int line = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                }
            }
            return new IllegalArgumentException("invalid JSON at line " + line + ": " + message);
        }
    }
}
