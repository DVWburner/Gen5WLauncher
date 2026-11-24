package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure Java 8 recursive-descent JSON parser for host JVM tests (replaces external JSON dependency).
 */
final class SimpleJsonParser {
    private final String src;
    private final int len;
    private int pos;

    private SimpleJsonParser(String src) {
        this.src = src == null ? "" : src;
        this.len = this.src.length();
    }

    static Object parse(String json) {
        SimpleJsonParser p = new SimpleJsonParser(json);
        p.skipWs();
        Object val = p.readValue();
        p.skipWs();
        if (p.pos != p.len) {
            throw new IllegalArgumentException("Trailing characters in JSON at index " + p.pos);
        }
        return val;
    }

    static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Not a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    static List<Object> parseArray(String json) {
        Object v = parse(json);
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("Not a JSON array");
        }
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) v;
        return l;
    }

    private void skipWs() {
        while (pos < len) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object readValue() {
        skipWs();
        if (pos >= len) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        char c = src.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't') return readLiteral("true", Boolean.TRUE);
        if (c == 'f') return readLiteral("false", Boolean.FALSE);
        if (c == 'n') return readLiteral("null", null);
        if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
        throw new IllegalArgumentException("Unexpected character '" + c + "' at index " + pos);
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        skipWs();
        if (peek('}')) {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (!peek('"')) {
                throw new IllegalArgumentException("Expected string key at index " + pos);
            }
            String key = readString();
            skipWs();
            expect(':');
            Object val = readValue();
            map.put(key, val);
            skipWs();
            if (peek('}')) {
                pos++;
                return map;
            }
            expect(',');
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<Object>();
        skipWs();
        if (peek(']')) {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWs();
            if (peek(']')) {
                pos++;
                return list;
            }
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < len) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= len) {
                    throw new IllegalArgumentException("Unterminated escape in JSON string");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > len) {
                            throw new IllegalArgumentException("Invalid \\u escape at index " + pos);
                        }
                        String hex = src.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid escape \\" + esc + " at index " + (pos - 1));
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private Object readLiteral(String lit, Object val) {
        if (!src.regionMatches(pos, lit, 0, lit.length())) {
            throw new IllegalArgumentException("Invalid literal at index " + pos);
        }
        pos += lit.length();
        return val;
    }

    private Number readNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        boolean isFloat = false;
        while (pos < len) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                if (pos == start) break;
                if (c == '+' || c == '-') {
                    char prev = src.charAt(pos - 1);
                    if (prev != 'e' && prev != 'E') break;
                }
                isFloat = true;
                pos++;
            } else {
                break;
            }
        }
        String raw = src.substring(start, pos);
        if (isFloat) {
            return Double.valueOf(Double.parseDouble(raw));
        }
        long l = Long.parseLong(raw);
        if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return Integer.valueOf((int) l);
        }
        return Long.valueOf(l);
    }

    private void expect(char expected) {
        if (pos >= len || src.charAt(pos) != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' at index " + pos);
        }
        pos++;
    }

    private boolean peek(char ch) {
        return pos < len && src.charAt(pos) == ch;
    }

    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeVal(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeVal(StringBuilder sb, Object v) {
        if (v == null || v == JSONObject.NULL) {
            sb.append("null");
        } else if (v instanceof JSONObject) {
            writeVal(sb, ((JSONObject) v).map);
        } else if (v instanceof JSONArray) {
            writeVal(sb, ((JSONArray) v).list);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeVal(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeVal(sb, item);
            }
            sb.append(']');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v.toString());
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }
}
