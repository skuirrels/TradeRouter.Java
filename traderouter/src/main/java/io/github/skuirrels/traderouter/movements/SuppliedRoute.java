package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.List;

/**
 * A complete road or rail result imported from an authoritative upstream routing system. Application and sample
 * code should use {@link RoadRouteProvider} or {@link RoadDistanceEstimator} for road legs rather than constructing
 * fixed values.
 *
 * @param distanceKm    route distance in kilometres
 * @param durationHours travelling time in hours, or null when unknown
 * @param geometry      route geometry in traversal order, or null when unknown
 * @param source        caller-defined provenance label; null defaults to "caller"
 */
public record SuppliedRoute(double distanceKm, Double durationHours, List<Coordinate> geometry, String source) {

    /** Defaults a null source to "caller" and copies the geometry. */
    public SuppliedRoute {
        source = source == null ? "caller" : source;
        geometry = geometry == null ? null : List.copyOf(geometry);
    }

    /** Creates a supplied route with only a distance and a provenance label. */
    public SuppliedRoute(double distanceKm, String source) {
        this(distanceKm, null, null, source);
    }
}
