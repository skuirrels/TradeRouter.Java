package io.github.skuirrels.traderouter.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 8259 JSON reader used to load the embedded datasets and to read GeoJSON geometry. It keeps the
 * library free of runtime dependencies. Objects become {@link LinkedHashMap}, arrays {@link ArrayList},
 * strings {@link String}, integers {@link Long}, other numbers {@link Double}, booleans {@link Boolean}
 * and null {@code null}.
 */
public final class JsonParser {
    private final String text;
    private int pos;

    private JsonParser(String text) {
        this.text = text;
    }

    /** Parses a complete JSON document. */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON text cannot be null.");
        }
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    /** Returns the value as a double, accepting any JSON number. */
    public static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("Expected a JSON number but found " + describe(value) + ".");
    }

    /** Returns the value as an int, accepting any JSON number with an integral value. */
    public static int asInt(Object value) {
        if (value instanceof Long l) {
            return Math.toIntExact(l);
        }
        if (value instanceof Double d && d == Math.rint(d)) {
            return Math.toIntExact((long) (double) d);
        }
        throw new IllegalArgumentException("Expected a JSON integer but found " + describe(value) + ".");
    }

    /** Returns the value as a string, or null when the value is JSON null. */
    public static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("Expected a JSON string but found " + describe(value) + ".");
    }

    /** Returns the value as a list. */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException("Expected a JSON array but found " + describe(value) + ".");
    }

    /** Returns the value as a map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected a JSON object but found " + describe(value) + ".");
    }

    /** Whether the value is a JSON number. */
    public static boolean isNumber(Object value) {
        return value instanceof Number;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?>) {
            return "an array";
        }
        if (value instanceof Map<?, ?>) {
            return "an object";
        }
        return value.getClass().getSimpleName();
    }

    private Object readValue() {
        if (pos >= text.length()) {
            throw error("Unexpected end of JSON");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected a property name");
            }
            String name = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("Expected ':'");
            }
            pos++;
            skipWhitespace();
            result.put(name, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return result;
            }
            throw error("Expected ',' or '}'");
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return result;
            }
            throw error("Expected ',' or ']'");
        }
    }

    private String readString() {
        pos++; // opening quote
        int start = pos;
        // Fast path: no escapes.
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '"') {
                String value = text.substring(start, pos);
                pos++;
                return value;
            }
            if (c == '\\') {
                break;
            }
            pos++;
        }

        StringBuilder sb = new StringBuilder().append(text, start, pos);
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                break;
            }
            char escape = text.charAt(pos++);
            switch (escape) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("Truncated unicode escape");
                    }
                    sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw error("Invalid escape '\\" + escape + "'");
            }
        }
        throw error("Unterminated string");
    }

    private Number readNumber() {
        int start = pos;
        boolean integral = true;
        if (peek() == '-') {
            pos++;
        }
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                integral = false;
                pos++;
            } else {
                break;
            }
        }
        String token = text.substring(start, pos);
        try {
            if (integral && token.length() < 19) {
                return Long.parseLong(token);
            }
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw error("Invalid number '" + token + "'");
        }
    }

    private void expectLiteral(String literal) {
        if (!text.startsWith(literal, pos)) {
            throw error("Expected '" + literal + "'");
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("Unexpected end of JSON");
        }
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pos++;
            } else {
                break;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + pos + ".");
    }
}
