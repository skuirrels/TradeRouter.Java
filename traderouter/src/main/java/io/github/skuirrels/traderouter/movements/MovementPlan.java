package io.github.skuirrels.traderouter.movements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, fluent description of a continuous multi-leg movement. Each destination automatically becomes
 * the next leg's origin, so every location code is stated once.
 */
public final class MovementPlan {
    private final List<MovementLeg> legs;
    private final Waypoint current;
    private final boolean complete;

    private MovementPlan(Waypoint current, List<MovementLeg> legs, boolean complete) {
        this.current = current;
        this.legs = Collections.unmodifiableList(new ArrayList<>(legs));
        this.complete = complete;
    }

    /** The legs added to the plan so far. */
    public List<MovementLeg> legs() {
        return legs;
    }

    /** Whether a delivery leg has completed the plan. */
    public boolean isComplete() {
        return complete;
    }

    /** Starts a movement plan at the given waypoint. */
    public static MovementPlan from(Waypoint origin) {
        Objects.requireNonNull(origin, "origin");
        return new MovementPlan(origin, List.of(), false);
    }

    /** Adds the first-mile pickup leg. A pickup can only be the first leg. */
    public MovementPlan pickupTo(Waypoint destination, TransportMode by) {
        if (!legs.isEmpty()) {
            throw new IllegalStateException("The pickup leg must be the first leg in a movement plan.");
        }
        return append(destination, by, LegKind.PICKUP, false);
    }

    /** Adds a main-carriage leg from the previous destination. */
    public MovementPlan thenTo(Waypoint destination, TransportMode by) {
        return append(destination, by, LegKind.MAIN, false);
    }

    /** Adds the final delivery leg and completes the plan. */
    public MovementPlan deliverTo(Waypoint destination, TransportMode by) {
        return append(destination, by, LegKind.DELIVERY, true);
    }

    private MovementPlan append(Waypoint destination, TransportMode mode, LegKind kind, boolean completesPlan) {
        Objects.requireNonNull(destination, "destination");
        if (complete) {
            throw new IllegalStateException("No leg can be added after the delivery leg.");
        }
        Objects.requireNonNull(mode, "mode");

        MovementLeg leg = new MovementLeg(
                current.location(),
                destination.location(),
                mode,
                kind,
                current.kind(),
                destination.kind());
        List<MovementLeg> extended = new ArrayList<>(legs);
        extended.add(leg);
        return new MovementPlan(destination, extended, completesPlan);
    }
}
