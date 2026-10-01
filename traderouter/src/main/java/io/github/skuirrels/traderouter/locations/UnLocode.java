package io.github.skuirrels.traderouter.locations;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.Objects;
import java.util.Set;

/**
 * One entry of the UNECE UN/LOCODE list, possibly enriched with an attributed coordinate.
 *
 * @param code             five-character UN/LOCODE
 * @param name             location name without diacritics
 * @param coordinate       UNECE position to one minute of arc, an attributed supplement position, or null
 * @param functions        recorded functions, with the sea-port or rail-terminal function added for codes listed in
 *                         the sea-port or rail supplement file
 * @param coordinateSource "UNECE" when the position comes from the code list, otherwise the researched source
 *                         named in the supplement file; empty when there is no position
 */
public record UnLocode(String code, String name, Coordinate coordinate, Set<LocationFunction> functions, String coordinateSource) {

    /** Creates an entry, defaulting null functions to none and a null source to empty. */
    public UnLocode {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        functions = functions == null ? LocationFunction.NONE : Set.copyOf(functions);
        coordinateSource = coordinateSource == null ? "" : coordinateSource;
    }

    /** Creates an entry with an empty coordinate source. */
    public UnLocode(String code, String name, Coordinate coordinate, Set<LocationFunction> functions) {
        this(code, name, coordinate, functions, "");
    }

    /** Two-letter ISO country code, the first two characters of the code. */
    public String country() {
        return code.substring(0, 2);
    }

    /** Whether the entry is flagged as a sea port. */
    public boolean isSeaPort() {
        return functions.contains(LocationFunction.SEA_PORT);
    }

    /** Whether the entry is flagged as an airport. */
    public boolean isAirport() {
        return functions.contains(LocationFunction.AIRPORT);
    }

    /** Whether the entry carries a coordinate. */
    public boolean hasCoordinate() {
        return coordinate != null;
    }
}
