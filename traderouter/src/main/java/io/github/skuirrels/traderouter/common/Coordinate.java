package io.github.skuirrels.traderouter.common;

import java.util.Locale;

/**
 * A geographic coordinate with longitude (X) and latitude (Y).
 *
 * @param longitude longitude in degrees, between -180 and 180 for standard coordinates or beyond for unwrapped lines
 * @param latitude  latitude in degrees, between -90 and 90
 */
public record Coordinate(double longitude, double latitude) {

    /**
     * Validates that the coordinate has a valid latitude and finite numbers.
     *
     * @throws IllegalArgumentException when the coordinate is invalid
     */
    public void validate() {
        if (Double.isNaN(latitude) || Double.isInfinite(latitude)
                || Double.isNaN(longitude) || Double.isInfinite(longitude)) {
            throw new IllegalArgumentException("Invalid coordinate values: (" + longitude + ", " + latitude + ")");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90 degrees. Received: " + latitude);
        }
    }

    /** Normalizes the longitude to the [-180, 180] range. */
    public Coordinate withNormalizedLongitude() {
        double normalizedLon = ((longitude % 360.0) + 540.0) % 360.0 - 180.0;
        return new Coordinate(normalizedLon, latitude);
    }

    /** Creates a coordinate from a {@code [longitude, latitude]} array. */
    public static Coordinate fromArray(double[] coords) {
        if (coords == null || coords.length < 2) {
            throw new IllegalArgumentException("Coordinate array must contain at least 2 elements [lon, lat].");
        }
        return new Coordinate(coords[0], coords[1]);
    }

    /** Returns the coordinate as a {@code [longitude, latitude]} array. */
    public double[] toArray() {
        return new double[] {longitude, latitude};
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "[%.6f, %.6f]", longitude, latitude);
    }
}
