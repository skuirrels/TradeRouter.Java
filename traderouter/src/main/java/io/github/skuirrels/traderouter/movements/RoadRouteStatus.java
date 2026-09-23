package io.github.skuirrels.traderouter.movements;

/** Outcome of asking an external provider to route one road leg. */
public enum RoadRouteStatus {
    /** A road-network route was found. */
    SUCCESS("Success"),

    /** One or both endpoints lie outside the provider's loaded road-network coverage. */
    OUTSIDE_COVERAGE("OutsideCoverage"),

    /** The endpoints are covered, but the provider found no traversable route. */
    NO_ROUTE("NoRoute"),

    /** The provider could not answer because it was unavailable or returned an unusable response. */
    UNAVAILABLE("Unavailable");

    private final String displayName;

    RoadRouteStatus(String displayName) {
        this.displayName = displayName;
    }

    /** Name used in warnings and error messages, for example "OutsideCoverage", matching the .NET library. */
    public String displayName() {
        return displayName;
    }
}
