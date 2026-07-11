package io.configd.kms.vault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON reader/writer sized to the two Vault endpoints this module speaks to.
 * The parser is a standard recursive-descent reader (RFC 8259 grammar: object, array, string with escapes
 * and {@code \\uXXXX}, number, {@code true}/{@code false}/{@code null}); the writer emits only the flat
 * request objects Vault expects (string/number/boolean values), escaping strings correctly.
 *
 * <p>It exists so the module stays free of any JSON library. It is deliberately small: it parses the handful
 * of fields Configd reads ({@code auth.client_token}, {@code data.plaintext}, {@code data.ciphertext},
 * {@code errors}) and is not a general-purpose serializer.
 */
final class Json {

    private Json() {
    }

    /**
     * Builds a flat JSON object from alternating key/value pairs. String values are JSON-escaped; Number and
     * Boolean values are emitted bare; a {@code null} value is skipped (Vault treats absent == default).
     */
    static String object(Object... keyValues) {
        if ((keyValues.length & 1) != 0) {
            throw new IllegalArgumentException("object() needs an even number of key/value args");
        }
        StringBuilder sb = new StringBuilder(64).append('{');
        boolean first = true;
        for (int i = 0; i < keyValues.length; i += 2) {
            Object v = keyValues[i + 1];
            if (v == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(keyValues[i]));
            sb.append(':');
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                writeString(sb, String.valueOf(v));
            }
        }
        return sb.append('}').toString();
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Parses a JSON document into Map/List/String/Double/Boolean/null. Throws on malformed input. */
    static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (!p.atEnd()) {
            throw new JsonException("trailing content after JSON value at " + p.pos);
        }
        return v;
    }

    /**
     * Navigates a dotted path through nested objects and returns the leaf {@code String}, or {@code null} if
     * any segment is missing or the leaf is not a string. E.g. {@code string(root, "data.plaintext")}.
     */
    @SuppressWarnings("unchecked")
    static String string(Object root, String dottedPath) {
        Object cur = root;
        for (String seg : dottedPath.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = ((Map<String, Object>) m).get(seg);
            if (cur == null) {
                return null;
            }
        }
        return (cur instanceof String s) ? s : null;
    }

    /** Thrown for malformed JSON (surfaces as a Vault-response error at the call site). */
    static final class JsonException extends RuntimeException {
        JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            skipWs();
            if (atEnd()) {
                throw new JsonException("unexpected end of JSON");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            for (;;) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("expected object key at " + pos);
                }
                String key = string();
                skipWs();
                expect(':');
                m.put(key, value());
                skipWs();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' in object at " + pos);
                }
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            for (;;) {
                list.add(value());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']' in array at " + pos);
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            for (;;) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new JsonException("truncated \\u escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Boolean bool() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("invalid literal at " + pos);
        }

        private Object nul() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("invalid literal at " + pos);
        }

        private Double number() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw new JsonException("invalid value at " + pos);
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number '" + s.substring(start, pos) + "'");
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("unexpected end of JSON");
            }
            return s.charAt(pos);
        }

        private char next() {
            if (atEnd()) {
                throw new JsonException("unexpected end of JSON");
            }
            return s.charAt(pos++);
        }

        private void expect(char c) {
            char got = next();
            if (got != c) {
                throw new JsonException("expected '" + c + "' but got '" + got + "' at " + (pos - 1));
            }
        }
    }
}
