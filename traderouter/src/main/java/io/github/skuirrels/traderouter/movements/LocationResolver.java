package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.Objects;
import java.util.Optional;

/**
 * Supplies coordinates for location codes that are neither in {@link MovementRequest#getCoordinates()}, the
 * embedded port list nor the embedded UN/LOCODE list with coordinates. It is consulted last.
 */
@FunctionalInterface
public interface LocationResolver {

    /**
     * A resolved position.
     *
     * @param coordinate the position
     * @param name       optional display name, or null
     */
    record Resolution(Coordinate coordinate, String name) {
        /** Validates the coordinate. */
        public Resolution {
            Objects.requireNonNull(coordinate, "coordinate");
        }
    }

    /** Attempts to resolve a location code to a coordinate, returning empty when the code is unknown. */
    Optional<Resolution> tryResolve(String code);
}
