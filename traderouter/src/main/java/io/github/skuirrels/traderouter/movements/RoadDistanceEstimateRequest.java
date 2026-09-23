package io.github.skuirrels.traderouter.movements;

/**
 * Input to the deterministic fallback estimator.
 *
 * @param sequence               one-based movement-leg sequence
 * @param from                   resolved road origin
 * @param to                     resolved road destination
 * @param straightLineDistanceKm great-circle lower bound in kilometres
 */
public record RoadDistanceEstimateRequest(
        int sequence,
        ResolvedLocation from,
        ResolvedLocation to,
        double straightLineDistanceKm) {
}
