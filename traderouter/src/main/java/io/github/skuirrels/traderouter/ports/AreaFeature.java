package io.github.skuirrels.traderouter.ports;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.PnPoly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A geographic area (polygon) associated with preferred maritime ports and share weights.
 */
public final class AreaFeature {
    private final String name;
    private final List<Coordinate> coordinates;
    private final List<PortProps> preferredPorts;
    private final double area;

    /** Creates an area with no preferred ports. */
    public AreaFeature(List<Coordinate> coordinates, String name) {
        this(coordinates, name, null);
    }

    /**
     * Creates an area.
     *
     * @param coordinates    polygon boundary vertices, at least three
     * @param name           name or identifier of the area, for example "BE" or "EUR"
     * @param preferredPorts preferred ports configured for this area, or null
     */
    public AreaFeature(List<Coordinate> coordinates, String name, List<PortProps> preferredPorts) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Area name cannot be null or whitespace.");
        }
        Objects.requireNonNull(coordinates, "coordinates");
        this.name = name;
        List<Coordinate> coordinateList = new ArrayList<>(coordinates);
        if (coordinateList.size() < 3) {
            throw new IllegalArgumentException("An area polygon requires at least three vertices.");
        }
        for (Coordinate coordinate : coordinateList) {
            Objects.requireNonNull(coordinate, "Area vertices cannot contain null.").validate();
        }
        this.coordinates = Collections.unmodifiableList(coordinateList);
        List<PortProps> preferredPortList = preferredPorts == null ? new ArrayList<>() : new ArrayList<>(preferredPorts);
        if (preferredPortList.contains(null)) {
            throw new IllegalArgumentException("Preferred ports cannot contain null entries.");
        }
        this.preferredPorts = Collections.unmodifiableList(preferredPortList);
        this.area = PnPoly.calculatePlanarArea(this.coordinates);
    }

    /** Name or identifier of the area. */
    public String name() {
        return name;
    }

    /** Polygon boundary vertices. */
    public List<Coordinate> coordinates() {
        return coordinates;
    }

    /** Preferred ports configured for this area. */
    public List<PortProps> preferredPorts() {
        return preferredPorts;
    }

    /** Planar area of the polygon. */
    public double area() {
        return area;
    }

    /** Checks whether a geographic coordinate is inside this area polygon. */
    public boolean contains(Coordinate point) {
        return PnPoly.containsPoint(coordinates, point);
    }

    /** Calculates the approximate distance in kilometres from a point to the nearest polygon edge. */
    public double distanceToPoint(Coordinate point) {
        if (coordinates.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        point.validate();
        if (contains(point)) {
            return 0.0;
        }

        final double earthRadiusKm = 6371.0088;
        double cosLatitude = Math.cos(point.latitude() * Math.PI / 180.0);
        double minDistanceSquared = Double.POSITIVE_INFINITY;
        for (int i = 0; i < coordinates.size(); i++) {
            Coordinate a = coordinates.get(i);
            Coordinate b = coordinates.get((i + 1) % coordinates.size());
            double unwrappedBx = a.longitude() + PnPoly.normalizeLongitudeDelta(b.longitude() - a.longitude());
            double segmentCentre = (a.longitude() + unwrappedBx) / 2.0;
            double mappedPointLongitude = point.longitude() + (360.0 * Math.rint((segmentCentre - point.longitude()) / 360.0));
            double ax = (a.longitude() - mappedPointLongitude) * Math.PI / 180.0 * cosLatitude;
            double ay = (a.latitude() - point.latitude()) * Math.PI / 180.0;
            double bx = (unwrappedBx - mappedPointLongitude) * Math.PI / 180.0 * cosLatitude;
            double by = (b.latitude() - point.latitude()) * Math.PI / 180.0;

            double dx = bx - ax;
            double dy = by - ay;
            double segmentLengthSquared = (dx * dx) + (dy * dy);
            double t = segmentLengthSquared == 0 ? 0 : clamp(((-ax * dx) + (-ay * dy)) / segmentLengthSquared, 0.0, 1.0);
            double closestX = ax + (t * dx);
            double closestY = ay + (t * dy);
            minDistanceSquared = Math.min(minDistanceSquared, (closestX * closestX) + (closestY * closestY));
        }

        return Math.sqrt(minDistanceSquared) * earthRadiusKm;
    }

    // Math.clamp needs Java 21; the library targets Java 17.
    private static double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}
