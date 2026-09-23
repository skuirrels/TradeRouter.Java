package io.github.skuirrels.traderouter.movements;

import java.util.Objects;

/**
 * Applies a configurable road-circuity factor to the great-circle lower bound. This is a coarse planning
 * estimate, not a road-network route; callers needing route-specific accuracy should configure a provider.
 */
public final class CircuityRoadDistanceEstimator implements RoadDistanceEstimator {
    /** Default road-to-great-circle multiplier. */
    public static final double DEFAULT_FACTOR = 1.3;

    private static final CircuityRoadDistanceEstimator DEFAULT = new CircuityRoadDistanceEstimator();

    private final double factor;

    /** Creates an estimator with the default multiplier. */
    public CircuityRoadDistanceEstimator() {
        this(DEFAULT_FACTOR);
    }

    /** Creates an estimator with the supplied multiplier, which must be finite and at least one. */
    public CircuityRoadDistanceEstimator(double factor) {
        if (!Double.isFinite(factor) || factor < 1.0) {
            throw new IllegalArgumentException("The road circuity factor must be finite and at least 1. Received: " + factor);
        }
        this.factor = factor;
    }

    /** Shared default estimator. */
    public static CircuityRoadDistanceEstimator getDefault() {
        return DEFAULT;
    }

    /** Road-to-great-circle multiplier. */
    public double factor() {
        return factor;
    }

    @Override
    public RoadDistanceEstimate estimate(RoadDistanceEstimateRequest request) {
        Objects.requireNonNull(request, "request");
        if (!Double.isFinite(request.straightLineDistanceKm()) || request.straightLineDistanceKm() < 0.0) {
            throw new IllegalArgumentException("Straight-line distance must be finite and non-negative.");
        }

        return new RoadDistanceEstimate(
                request.straightLineDistanceKm() * factor,
                "circuity-" + ModelNames.format(factor, 3),
                "Estimated from great-circle distance because no road-network route was available.");
    }
}
