package io.github.skuirrels.traderouter.movements;

/**
 * Estimates road distance when no road network is available. The .NET library calls this interface
 * {@code IRoadDistanceEstimator}.
 */
@FunctionalInterface
public interface RoadDistanceEstimator {
    /** Estimates a road distance from resolved endpoints and their great-circle separation. */
    RoadDistanceEstimate estimate(RoadDistanceEstimateRequest request);
}
