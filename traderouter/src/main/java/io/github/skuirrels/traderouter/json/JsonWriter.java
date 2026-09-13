package io.github.skuirrels.traderouter.json;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal streaming JSON writer producing compact or two-space indented output. It writes numbers with the
 * shortest representation that round-trips, and integral doubles without a decimal point.
 */
public final class JsonWriter {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final StringBuilder out = new StringBuilder(256);
    private final boolean indented;
    private final Deque<Boolean> firstInScope = new ArrayDeque<>();
    private boolean pendingName;

    /** Creates a writer producing compact or indented output. */
    public JsonWriter(boolean indented) {
        this.indented = indented;
    }

    /** Begins a JSON object. */
    public JsonWriter beginObject() {
        beginValue();
        out.append('{');
        firstInScope.push(Boolean.TRUE);
        return this;
    }

    /** Ends the current JSON object. */
    public JsonWriter endObject() {
        boolean wasEmpty = firstInScope.pop();
        if (!wasEmpty) {
            newLine();
        }
        out.append('}');
        return this;
    }

    /** Begins a JSON array. */
    public JsonWriter beginArray() {
        beginValue();
        out.append('[');
        firstInScope.push(Boolean.TRUE);
        return this;
    }

    /** Ends the current JSON array. */
    public JsonWriter endArray() {
        boolean wasEmpty = firstInScope.pop();
        if (!wasEmpty) {
            newLine();
        }
        out.append(']');
        return this;
    }

    /** Writes a property name; the next value written belongs to it. */
    public JsonWriter name(String name) {
        separator();
        writeString(name);
        out.append(indented ? ": " : ":");
        pendingName = true;
        return this;
    }

    /** Writes a string value, or null. */
    public JsonWriter value(String value) {
        if (value == null) {
            return nullValue();
        }
        beginValue();
        writeString(value);
        return this;
    }

    /** Writes a numeric value. */
    public JsonWriter value(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON cannot represent " + value + ".");
        }
        beginValue();
        out.append(formatDouble(value));
        return this;
    }

    /** Writes an integer value. */
    public JsonWriter value(long value) {
        beginValue();
        out.append(value);
        return this;
    }

    /** Writes a boolean value. */
    public JsonWriter value(boolean value) {
        beginValue();
        out.append(value);
        return this;
    }

    /** Writes a JSON null. */
    public JsonWriter nullValue() {
        beginValue();
        out.append("null");
        return this;
    }

    /** Writes a {@code [longitude, latitude]} position. */
    public JsonWriter position(double[] position) {
        beginArray();
        for (double ordinate : position) {
            value(ordinate);
        }
        return endArray();
    }

    /** The JSON written so far. */
    @Override
    public String toString() {
        return out.toString();
    }

    /** Formats a finite double the way the library writes it to JSON. */
    public static String formatDouble(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            long asLong = (long) value;
            if (asLong == 0 && 1.0 / value < 0) {
                return "-0";
            }
            return Long.toString(asLong);
        }
        return Double.toString(value);
    }

    private void beginValue() {
        if (pendingName) {
            pendingName = false;
            return;
        }
        separator();
    }

    private void separator() {
        if (firstInScope.isEmpty()) {
            return;
        }
        boolean first = firstInScope.pop();
        firstInScope.push(Boolean.FALSE);
        if (!first) {
            out.append(',');
        }
        newLine();
    }

    private void newLine() {
        if (!indented) {
            return;
        }
        out.append('\n');
        for (int i = 0; i < firstInScope.size(); i++) {
            out.append("  ");
        }
    }

    private void writeString(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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
                        out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
