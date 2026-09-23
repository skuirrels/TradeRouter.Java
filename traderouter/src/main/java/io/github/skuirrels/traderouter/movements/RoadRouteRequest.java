package io.github.skuirrels.traderouter.movements;

import java.util.Objects;

/**
 * Resolved input passed to a road-network provider.
 *
 * @param sequence one-based movement-leg sequence
 * @param from     resolved road origin
 * @param to       resolved road destination
 */
public record RoadRouteRequest(int sequence, ResolvedLocation from, ResolvedLocation to) {

    /** Validates the required members. */
    public RoadRouteRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
