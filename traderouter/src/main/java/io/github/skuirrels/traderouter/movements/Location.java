package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.Locale;

/**
 * A place referenced by a movement leg: a UN/LOCODE, a coordinate, or both.
 */
public final class Location {
    private final String code;
    private final Coordinate coordinate;
    private final String name;

    private Location(String code, Coordinate coordinate, String name) {
        this.code = code;
        this.coordinate = coordinate;
        this.name = name;
    }

    /** Creates a location from a code that will be resolved against the port database or a resolver. */
    public static Location fromCode(String code) {
        return new Location(normalizeCode(code), null, null);
    }

    /** Creates a location from a code with a known coordinate, bypassing lookup. */
    public static Location fromCode(String code, Coordinate coordinate) {
        return fromCode(code, coordinate, null);
    }

    /** Creates a location from a code with a known coordinate and display name, bypassing lookup. */
    public static Location fromCode(String code, Coordinate coordinate, String name) {
        return new Location(normalizeCode(code), coordinate, name);
    }

    /** Creates a location from a coordinate alone. */
    public static Location fromCoordinate(Coordinate coordinate) {
        return fromCoordinate(coordinate, null);
    }

    /** Creates a location from a coordinate with a display name. */
    public static Location fromCoordinate(Coordinate coordinate, String name) {
        if (coordinate == null) {
            throw new IllegalArgumentException("Location coordinate cannot be null.");
        }
        return new Location(null, coordinate, name);
    }

    /** UN/LOCODE or other location code, upper-cased. Null when only a coordinate was given. */
    public String code() {
        return code;
    }

    /** Explicit coordinate, or null. When set it takes precedence over any lookup of {@link #code()}. */
    public Coordinate coordinate() {
        return coordinate;
    }

    /** Optional display name, or null. */
    public String name() {
        return name;
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Location code cannot be null or whitespace.");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        if (code != null) {
            return code;
        }
        if (name != null) {
            return name;
        }
        return coordinate != null ? coordinate.toString() : "?";
    }
}
