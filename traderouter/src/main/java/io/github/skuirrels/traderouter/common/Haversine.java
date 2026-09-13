package io.github.skuirrels.traderouter.common;

import java.util.List;
import java.util.Objects;

/**
 * Geodesic and great-circle distance calculations.
 */
public final class Haversine {
    private static final double AVG_EARTH_RADIUS_METERS = 6371008.8;
    private static final double EARTH_RADIUS_KM = AVG_EARTH_RADIUS_METERS / 1000.0;

    private Haversine() {
    }

    /** A point on the unit sphere. */
    public record UnitVector(double x, double y, double z) {
    }

    /** Calculates the great-circle distance between two coordinates in kilometres. */
    public static double distance(Coordinate c1, Coordinate c2) {
        return distance(c1, c2, DistanceUnit.KM);
    }

    /** Calculates the great-circle distance between two coordinates in the specified unit. */
    public static double distance(Coordinate c1, Coordinate c2, DistanceUnit unit) {
        c1.validate();
        c2.validate();
        DistanceUnit.requireUnit(unit);
        return distanceUnchecked(c1, c2, unit);
    }

    private static double distanceUnchecked(Coordinate c1, Coordinate c2, DistanceUnit unit) {
        double dLat = toRadians(c2.latitude() - c1.latitude());
        double dLon = toRadians(c2.longitude() - c1.longitude());

        double lat1 = toRadians(c1.latitude());
        double lat2 = toRadians(c2.latitude());

        double sinHalfDLat = Math.sin(dLat / 2.0);
        double sinHalfDLon = Math.sin(dLon / 2.0);

        double a = (sinHalfDLat * sinHalfDLat) + (sinHalfDLon * sinHalfDLon * Math.cos(lat1) * Math.cos(lat2));
        a = Math.clamp(a, 0.0, 1.0);
        double b = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        return b * AVG_EARTH_RADIUS_METERS * unit.conversionFactorFromMeters();
    }

    /** Calculates the Haversine distance in kilometres directly. Used as the A* heuristic. */
    public static double distanceKm(Coordinate c1, Coordinate c2) {
        c1.validate();
        c2.validate();
        return distanceKmUnchecked(c1, c2);
    }

    /**
     * Calculates the Haversine distance in kilometres without validating the coordinates. Intended for hot
     * paths over coordinates that were validated when they entered the library.
     */
    public static double distanceKmUnchecked(Coordinate c1, Coordinate c2) {
        double lat1 = toRadians(c1.latitude());
        double lat2 = toRadians(c2.latitude());
        double dLat = lat2 - lat1;
        double dLon = toRadians(c2.longitude() - c1.longitude());

        double sinHalfDLat = Math.sin(dLat / 2.0);
        double sinHalfDLon = Math.sin(dLon / 2.0);

        double a = (sinHalfDLat * sinHalfDLat) + (Math.cos(lat1) * Math.cos(lat2) * sinHalfDLon * sinHalfDLon);
        a = Math.clamp(a, 0.0, 1.0);
        return EARTH_RADIUS_KM * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    /** Calculates the total path length of a sequence of coordinates in kilometres. */
    public static double calculatePathLength(List<Coordinate> coordinates) {
        return calculatePathLength(coordinates, DistanceUnit.KM);
    }

    /** Calculates the total path length of a sequence of coordinates in the specified unit. */
    public static double calculatePathLength(List<Coordinate> coordinates, DistanceUnit unit) {
        Objects.requireNonNull(coordinates, "coordinates");
        DistanceUnit.requireUnit(unit);
        for (Coordinate coordinate : coordinates) {
            coordinate.validate();
        }
        if (coordinates.size() < 2) {
            return 0.0;
        }

        double total = 0.0;
        Coordinate previous = coordinates.get(0);
        for (int i = 1; i < coordinates.size(); i++) {
            Coordinate current = coordinates.get(i);
            total += distanceUnchecked(previous, current, unit);
            previous = current;
        }
        return total;
    }

    /** Calculates route duration in hours given vessel speed in knots and total path length in the unit. */
    public static double calculateDurationHours(double speedKnots, double length, DistanceUnit unit) {
        if (!Double.isFinite(speedKnots) || speedKnots <= 0) {
            throw new IllegalArgumentException("Speed must be a finite positive value. Received: " + speedKnots);
        }
        if (!Double.isFinite(length) || length < 0) {
            throw new IllegalArgumentException("Length must be a finite non-negative value. Received: " + length);
        }
        DistanceUnit.requireUnit(unit);
        if (length == 0) {
            return 0.0;
        }

        double speedInUnit = speedKnots * unit.speedCoefficient();
        return length / speedInUnit;
    }

    /** Calculates squared chord distance on a unit sphere. Its ordering is identical to great-circle distance. */
    public static double unitSphereDistanceSquared(Coordinate c1, Coordinate c2) {
        UnitVector a = toUnitVector(c1);
        UnitVector b = toUnitVector(c2);
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    /** Converts a coordinate to a point on the unit sphere. */
    public static UnitVector toUnitVector(Coordinate coordinate) {
        coordinate.validate();
        double lat = toRadians(coordinate.latitude());
        double lon = toRadians(coordinate.longitude());
        double cosLat = Math.cos(lat);
        return new UnitVector(cosLat * Math.cos(lon), cosLat * Math.sin(lon), Math.sin(lat));
    }

    private static double toRadians(double degrees) {
        return degrees * (Math.PI / 180.0);
    }
}
