package io.github.skuirrels.traderouter.movements;

/** Controls whether rail legs are routed on the embedded rail network or measured as a straight line. */
public enum RailRoutingMode {
    /**
     * Route on the embedded North American rail network when both ends lie within
     * {@link MovementRequest#getRailNetworkSnapKm()} of a connected line; otherwise use great-circle distance with a
     * warning.
     */
    PREFER_NETWORK_THEN_GREAT_CIRCLE,

    /** Always use great-circle distance, as before the rail network was embedded. */
    GREAT_CIRCLE_ONLY
}
