package io.github.skuirrels.traderouter.movements;

import java.util.Objects;

/**
 * Estimates road distance with a distance-decay circuity model. The road-to-straight-line ratio is higher for
 * short journeys and gradually declines with distance, reflecting the proportionally larger effect of local
 * access and road-network topology near each endpoint.
 */
public final class DistanceDecayRoadDistanceEstimator implements RoadDistanceEstimator {
    /** Coefficient fitted to the documented OSRM calibration set, with distance expressed in kilometres. */
    public static final double DEFAULT_COEFFICIENT = 1.6173976178145946;

    /** Exponent fitted to the documented OSRM calibration set. */
    public static final double DEFAULT_EXPONENT = 0.9579701056959417;

    private static final DistanceDecayRoadDistanceEstimator DEFAULT = new DistanceDecayRoadDistanceEstimator();

    private final double coefficient;
    private final double exponent;

    /** Creates an estimator with the calibrated default coefficient and exponent. */
    public DistanceDecayRoadDistanceEstimator() {
        this(DEFAULT_COEFFICIENT, DEFAULT_EXPONENT);
    }

    /**
     * Creates an estimator for {@code coefficient × straightLineDistance^exponent}. The coefficient must be finite
     * and at least one; the exponent must be finite, greater than zero and no greater than one.
     */
    public DistanceDecayRoadDistanceEstimator(double coefficient, double exponent) {
        if (!Double.isFinite(coefficient) || coefficient < 1.0) {
            throw new IllegalArgumentException("The coefficient must be finite and at least 1. Received: " + coefficient);
        }
        if (!Double.isFinite(exponent) || exponent <= 0.0 || exponent > 1.0) {
            throw new IllegalArgumentException(
                    "The exponent must be finite, greater than 0 and no greater than 1. Received: " + exponent);
        }
        this.coefficient = coefficient;
        this.exponent = exponent;
    }

    /** Shared default estimator. */
    public static DistanceDecayRoadDistanceEstimator getDefault() {
        return DEFAULT;
    }

    /** Multiplier in the power function, where input and output are kilometres. */
    public double coefficient() {
        return coefficient;
    }

    /** Exponent in the power function. Values below one make circuity decline with distance. */
    public double exponent() {
        return exponent;
    }

    @Override
    public RoadDistanceEstimate estimate(RoadDistanceEstimateRequest request) {
        Objects.requireNonNull(request, "request");
        double straightLineDistanceKm = request.straightLineDistanceKm();
        if (!Double.isFinite(straightLineDistanceKm) || straightLineDistanceKm < 0.0) {
            throw new IllegalArgumentException("Straight-line distance must be finite and non-negative.");
        }

        double modelledDistanceKm = straightLineDistanceKm == 0.0
                ? 0.0
                : coefficient * Math.pow(straightLineDistanceKm, exponent);

        return new RoadDistanceEstimate(
                Math.max(straightLineDistanceKm, modelledDistanceKm),
                "distance-decay-" + ModelNames.format(coefficient, 5) + "x^" + ModelNames.format(exponent, 5),
                "Estimated with a distance-decay circuity model because no road-network route was available.");
    }
}
