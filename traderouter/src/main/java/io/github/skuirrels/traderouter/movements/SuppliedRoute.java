package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.List;

/**
 * A complete road result imported from an authoritative upstream routing system. Application and sample code
 * should use {@link RoadRouteProvider} or {@link RoadDistanceEstimator} rather than constructing fixed values.
 *
 * @param distanceKm    road distance in kilometres
 * @param durationHours travelling time in hours, or null when unknown
 * @param geometry      road geometry in traversal order, or null when unknown
 * @param source        caller-defined provenance label; null defaults to "caller"
 */
public record SuppliedRoadRoute(double distanceKm, Double durationHours, List<Coordinate> geometry, String source) {

    /** Defaults a null source to "caller" and copies the geometry. */
    public SuppliedRoadRoute {
        source = source == null ? "caller" : source;
        geometry = geometry == null ? null : List.copyOf(geometry);
    }

    /** Creates a supplied route with only a distance and a provenance label. */
    public SuppliedRoadRoute(double distanceKm, String source) {
        this(distanceKm, null, null, source);
    }
}
