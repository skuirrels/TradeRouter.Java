package io.github.skuirrels.traderouter.movements;

/** Controls how travelling time is calculated for road legs. */
public enum RoadDurationMode {
    /** Calculate duration from routed or estimated distance and the configured road speed. */
    CONFIGURED_SPEED,

    /**
     * Use a duration returned by a road provider or authoritative imported route when available; otherwise
     * calculate it from distance and the configured road speed.
     */
    ROUTE_DURATION_WHEN_AVAILABLE
}
