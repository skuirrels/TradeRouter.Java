package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.passages.Passage;
import io.github.skuirrels.traderouter.ports.PortParameters;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Options configuring sea route calculations. Setters return this instance for chaining.
 */
public final class TradeRouterOptions {
    /** The default pathfinding algorithm. */
    public static final String DIJKSTRA = "dijkstra";

    /** The A* pathfinding algorithm. */
    public static final String ASTAR = "astar";

    private DistanceUnit units = DistanceUnit.KM;
    private double speedKnots = 16.0;
    private boolean appendOriginDestination;
    private Set<String> restrictions = newRestrictionSet();
    private boolean allowNorthwest;
    private boolean includePorts;
    private PortParameters portParameters;
    private boolean returnPassages;
    private boolean returnSeaAreas;
    private String algorithm = DIJKSTRA;

    /** Creates options with the defaults: kilometres, 16 knots, no restrictions, Northwest Passage closed, Dijkstra. */
    public TradeRouterOptions() {
    }

    /** Unit for measuring route distance. Defaults to {@link DistanceUnit#KM}. */
    public DistanceUnit getUnits() {
        return units;
    }

    /** Sets the distance unit. */
    public TradeRouterOptions setUnits(DistanceUnit units) {
        this.units = units;
        return this;
    }

    /** Unit as string, for example "km", "mi" or "naut". */
    public String getUnitString() {
        return units.unitString();
    }

    /** Sets the unit from a string; see {@link DistanceUnit#parse(String)}. */
    public TradeRouterOptions setUnitString(String unitString) {
        this.units = DistanceUnit.parse(unitString);
        return this;
    }

    /**
     * Vessel speed in knots (nautical miles per hour). Default is 16 knots, a typical slow-steaming service
     * speed; the container fleet averaged under 14 knots in 2023 and design speeds of 22 to 25 knots are
     * rarely used.
     */
    public double getSpeedKnots() {
        return speedKnots;
    }

    /** Sets the vessel speed in knots. */
    public TradeRouterOptions setSpeedKnots(double speedKnots) {
        this.speedKnots = speedKnots;
        return this;
    }

    /** Whether to prepend the exact origin and append the exact destination to the route geometry. Default false. */
    public boolean isAppendOriginDestination() {
        return appendOriginDestination;
    }

    /** Sets whether the exact endpoints are added to the geometry. */
    public TradeRouterOptions setAppendOriginDestination(boolean appendOriginDestination) {
        this.appendOriginDestination = appendOriginDestination;
        return this;
    }

    /**
     * Passages to avoid, for example Suez, Panama or Gibraltar, matched case-insensitively. Default is empty.
     * The Northwest Passage is closed separately unless {@link #isAllowNorthwest()} is true. The set is live and
     * may be modified.
     */
    public Set<String> getRestrictions() {
        return restrictions;
    }

    /** Replaces the restriction set with a copy of the given passages. */
    public TradeRouterOptions setRestrictions(Collection<String> restrictions) {
        if (restrictions == null) {
            throw new IllegalArgumentException("Restrictions cannot be null.");
        }
        Set<String> copy = newRestrictionSet();
        for (String restriction : restrictions) {
            if (restriction == null) {
                throw new IllegalArgumentException("Restrictions cannot contain null.");
            }
            copy.add(restriction);
        }
        this.restrictions = copy;
        return this;
    }

    /** Replaces the restriction set with the given passages. */
    public TradeRouterOptions setRestrictions(String... restrictions) {
        return setRestrictions(java.util.Arrays.asList(restrictions));
    }

    /**
     * Whether routes may use the seasonal, ice-dependent Arctic lanes tagged {@link Passage#NORTHWEST}. Default
     * false, so replacing the restrictions never reopens the Arctic. Listing {@link Passage#NORTHWEST} in
     * {@link #getRestrictions()} keeps it closed even when this is true.
     */
    public boolean isAllowNorthwest() {
        return allowNorthwest;
    }

    /** Sets whether the Northwest Passage and other Arctic lanes are open. */
    public TradeRouterOptions setAllowNorthwest(boolean allowNorthwest) {
        this.allowNorthwest = allowNorthwest;
        return this;
    }

    /**
     * Returns every passage closed to the search: {@link #getRestrictions()}, plus {@link Passage#NORTHWEST}
     * unless {@link #isAllowNorthwest()} is true. The returned set is a new copy.
     */
    public Set<String> getClosedPassages() {
        if (restrictions == null) {
            throw new IllegalArgumentException("Restrictions cannot be null.");
        }
        Set<String> closed = newRestrictionSet();
        closed.addAll(restrictions);
        if (!allowNorthwest) {
            closed.add(Passage.NORTHWEST);
        }
        return closed;
    }

    /** Whether to route through the nearest ports close to origin and destination. Default false. */
    public boolean isIncludePorts() {
        return includePorts;
    }

    /** Sets whether to route via the nearest ports. */
    public TradeRouterOptions setIncludePorts(boolean includePorts) {
        this.includePorts = includePorts;
        return this;
    }

    /** Optional port selection and filtering parameters, or null. */
    public PortParameters getPortParameters() {
        return portParameters;
    }

    /** Sets the port parameters. */
    public TradeRouterOptions setPortParameters(PortParameters portParameters) {
        this.portParameters = portParameters;
        return this;
    }

    /** Whether to return the list of traversed passages in the route properties. Default false. */
    public boolean isReturnPassages() {
        return returnPassages;
    }

    /** Sets whether traversed passages are reported. */
    public TradeRouterOptions setReturnPassages(boolean returnPassages) {
        this.returnPassages = returnPassages;
        return this;
    }

    /**
     * Whether to return the named sea areas the route passes through, with where it enters and leaves each, in the
     * route properties. Default false.
     */
    public boolean isReturnSeaAreas() {
        return returnSeaAreas;
    }

    /** Sets whether traversed sea areas are reported. */
    public TradeRouterOptions setReturnSeaAreas(boolean returnSeaAreas) {
        this.returnSeaAreas = returnSeaAreas;
        return this;
    }

    /** Pathfinding algorithm: "dijkstra" (default) or "astar". */
    public String getAlgorithm() {
        return algorithm;
    }

    /** Sets the pathfinding algorithm. */
    public TradeRouterOptions setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    /** Returns a copy of these options with an independent restriction set. */
    public TradeRouterOptions copy() {
        if (restrictions == null) {
            throw new IllegalArgumentException("Restrictions cannot be null.");
        }
        TradeRouterOptions copy = new TradeRouterOptions()
                .setUnits(units)
                .setSpeedKnots(speedKnots)
                .setAppendOriginDestination(appendOriginDestination)
                .setRestrictions(restrictions)
                .setAllowNorthwest(allowNorthwest)
                .setIncludePorts(includePorts)
                .setPortParameters(portParameters == null ? null : portParameters.copy())
                .setReturnPassages(returnPassages)
                .setReturnSeaAreas(returnSeaAreas);
        copy.algorithm = algorithm;
        return copy;
    }

    /** Validates values that affect routing, timing and passage selection. */
    public void validate() {
        if (units == null) {
            throw new IllegalArgumentException("Distance unit cannot be null.");
        }
        if (!Double.isFinite(speedKnots) || speedKnots <= 0) {
            throw new IllegalArgumentException("Vessel speed must be finite and positive. Received: " + speedKnots);
        }
        if (restrictions == null) {
            throw new IllegalArgumentException("Restrictions cannot be null.");
        }
        for (String restriction : restrictions) {
            if (restriction == null || restriction.isBlank() || !Passage.isValid(restriction)) {
                throw new IllegalArgumentException(
                        "Unknown passage restriction '" + restriction + "'. Use a value from Passage.VALID_PASSAGES.");
            }
        }
        if (!DIJKSTRA.equalsIgnoreCase(algorithm) && !ASTAR.equalsIgnoreCase(algorithm)) {
            throw new IllegalArgumentException(
                    "Unknown pathfinding algorithm '" + algorithm + "'. Expected 'dijkstra' or 'astar'.");
        }
        if (portParameters != null) {
            portParameters.validate();
        }
    }

    private static Set<String> newRestrictionSet() {
        return new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    @Override
    public String toString() {
        return "TradeRouterOptions[units=" + units + ", speedKnots=" + speedKnots
                + ", appendOriginDestination=" + appendOriginDestination + ", restrictions=" + restrictions
                + ", includePorts=" + includePorts + ", returnPassages=" + returnPassages
                + ", returnSeaAreas=" + returnSeaAreas
                + ", algorithm=" + Objects.toString(algorithm) + "]";
    }
}
