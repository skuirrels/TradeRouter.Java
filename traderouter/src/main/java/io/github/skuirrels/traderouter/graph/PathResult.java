package io.github.skuirrels.traderouter.graph;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.List;

/**
 * Result of a shortest-path search.
 *
 * @param lengthKm total path length in kilometres, or positive infinity when no path exists
 * @param path     node coordinates from source to target; empty when no path exists. The list is freshly
 *                 allocated for every query and may be modified by the caller.
 */
public record PathResult(double lengthKm, List<Coordinate> path) {

    /** Whether a path was found. */
    public boolean found() {
        return !Double.isInfinite(lengthKm) && !path.isEmpty();
    }
}
