package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.Objects;

/**
 * A typed endpoint in a movement plan. The factory method records both the location code and its transport
 * role.
 */
public final class Waypoint {
    private final Location location;
    private final WaypointKind kind;

    private Waypoint(Location location, WaypointKind kind) {
        this.location = location;
        this.kind = kind;
    }

    /** The underlying location. */
    public Location location() {
        return location;
    }

    /** The endpoint's declared transport role. */
    public WaypointKind kind() {
        return kind;
    }

    /** The endpoint's UN/LOCODE, or null for a coordinate-only waypoint. */
    public String code() {
        return location.code();
    }

    /** Creates a general place identified by UN/LOCODE. */
    public static Waypoint place(String unLocode) {
        return fromCode(unLocode, WaypointKind.PLACE);
    }

    /** Creates a maritime port identified by UN/LOCODE. */
    public static Waypoint port(String unLocode) {
        return fromCode(unLocode, WaypointKind.PORT);
    }

    /** Creates an airport identified by UN/LOCODE. */
    public static Waypoint airport(String unLocode) {
        return fromCode(unLocode, WaypointKind.AIRPORT);
    }

    /** Creates a rail station identified by UN/LOCODE. */
    public static Waypoint station(String unLocode) {
        return fromCode(unLocode, WaypointKind.STATION);
    }

    /** Creates a transport terminal identified by UN/LOCODE. */
    public static Waypoint terminal(String unLocode) {
        return fromCode(unLocode, WaypointKind.TERMINAL);
    }

    /** Creates a depot identified by UN/LOCODE. */
    public static Waypoint depot(String unLocode) {
        return fromCode(unLocode, WaypointKind.DEPOT);
    }

    /** Creates an unclassified waypoint at an exact coordinate. */
    public static Waypoint at(Coordinate coordinate) {
        return at(coordinate, null);
    }

    /** Creates an unclassified, named waypoint at an exact coordinate. */
    public static Waypoint at(Coordinate coordinate, String name) {
        return new Waypoint(Location.fromCoordinate(coordinate, name), WaypointKind.UNSPECIFIED);
    }

    /** Creates an unclassified waypoint from a location supplied by the caller. */
    public static Waypoint from(Location location) {
        return from(location, WaypointKind.UNSPECIFIED);
    }

    /** Creates a typed waypoint from a location supplied by the caller. */
    public static Waypoint from(Location location, WaypointKind kind) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(kind, "kind");
        return new Waypoint(location, kind);
    }

    private static Waypoint fromCode(String unLocode, WaypointKind kind) {
        return new Waypoint(Location.fromCode(unLocode), kind);
    }

    @Override
    public String toString() {
        return kind.displayName() + " " + location;
    }
}
