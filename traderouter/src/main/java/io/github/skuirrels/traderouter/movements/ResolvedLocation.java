package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.ports.Port;

import java.util.Objects;
import java.util.Set;

/**
 * A location after its coordinate has been determined.
 *
 * @param code       location code, or null
 * @param name       display name, or null
 * @param coordinate resolved coordinate
 * @param port       matching port from the embedded database when the position came from it, or null
 * @param source     where the position came from: "coordinates", "ports", "unlocode" or "resolver"
 * @param functions  known UN/LOCODE functions, or null for an unclassified custom coordinate
 */
public record ResolvedLocation(
        String code,
        String name,
        Coordinate coordinate,
        Port port,
        String source,
        Set<LocationFunction> functions) {

    /** Validates the required members. */
    public ResolvedLocation {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(source, "source");
        functions = functions == null ? null : Set.copyOf(functions);
    }

    /** Creates a resolved location with unknown functions. */
    public ResolvedLocation(String code, String name, Coordinate coordinate, Port port, String source) {
        this(code, name, coordinate, port, source, null);
    }

    /** Best available label: code, then name, then coordinate. */
    public String label() {
        if (code != null) {
            return code;
        }
        return name != null ? name : coordinate.toString();
    }
}
