package io.github.skuirrels.traderouter.movements;

/**
 * A deterministic road-distance estimate and the model that produced it.
 *
 * @param distanceKm estimated road distance in kilometres
 * @param model      stable estimator identifier
 * @param warning    explanation of the estimate's limitations
 */
public record RoadDistanceEstimate(double distanceKm, String model, String warning) {
}
