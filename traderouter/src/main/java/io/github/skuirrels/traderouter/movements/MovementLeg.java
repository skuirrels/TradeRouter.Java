package io.github.skuirrels.traderouter.movements;

import java.util.Objects;

/**
 * One leg of a movement: from one location to another by one transport mode.
 */
public final class MovementLeg {
    private final Location from;
    private final Location to;
    private final TransportMode mode;
    private final LegKind kind;
    private final WaypointKind fromKind;
    private final WaypointKind toKind;

    /** Creates a main-carriage leg with unspecified waypoint kinds. */
    public MovementLeg(Location from, Location to, TransportMode mode) {
        this(from, to, mode, LegKind.MAIN, WaypointKind.UNSPECIFIED, WaypointKind.UNSPECIFIED);
    }

    /** Creates a leg of the given kind with unspecified waypoint kinds. */
    public MovementLeg(Location from, Location to, TransportMode mode, LegKind kind) {
        this(from, to, mode, kind, WaypointKind.UNSPECIFIED, WaypointKind.UNSPECIFIED);
    }

    /** Creates a leg. */
    public MovementLeg(
            Location from,
            Location to,
            TransportMode mode,
            LegKind kind,
            WaypointKind fromKind,
            WaypointKind toKind) {
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.fromKind = Objects.requireNonNull(fromKind, "fromKind");
        this.toKind = Objects.requireNonNull(toKind, "toKind");
    }

    /** Convenience constructor from two location codes. */
    public MovementLeg(String fromCode, String toCode, TransportMode mode) {
        this(Location.fromCode(fromCode), Location.fromCode(toCode), mode);
    }

    /** Convenience constructor from two location codes and a kind. */
    public MovementLeg(String fromCode, String toCode, TransportMode mode, LegKind kind) {
        this(Location.fromCode(fromCode), Location.fromCode(toCode), mode, kind);
    }

    /** Convenience constructor from two location codes with waypoint kinds. */
    public MovementLeg(
            String fromCode,
            String toCode,
            TransportMode mode,
            LegKind kind,
            WaypointKind fromKind,
            WaypointKind toKind) {
        this(Location.fromCode(fromCode), Location.fromCode(toCode), mode, kind, fromKind, toKind);
    }

    /** Start of the leg. */
    public Location from() {
        return from;
    }

    /** End of the leg. */
    public Location to() {
        return to;
    }

    /** Transport mode used for the leg. */
    public TransportMode mode() {
        return mode;
    }

    /** Role of the leg within the movement. */
    public LegKind kind() {
        return kind;
    }

    /** Declared semantic type of the start point. */
    public WaypointKind fromKind() {
        return fromKind;
    }

    /** Declared semantic type of the end point. */
    public WaypointKind toKind() {
        return toKind;
    }

    @Override
    public String toString() {
        return kind.displayName() + " " + from + " to " + to + " " + mode.displayName();
    }
}
