package io.github.skuirrels.traderouter.movements;

/** Controls how road legs choose between a configured network provider and the built-in estimate. */
public enum RoadRoutingMode {
    /** Use a configured provider when possible, otherwise return a labelled built-in estimate. */
    PREFER_NETWORK_THEN_ESTIMATE,

    /** Require every road leg to be resolved by a configured network provider or imported upstream result. */
    REQUIRE_NETWORK,

    /** Do not call a configured provider; use the built-in estimate or an imported upstream result. */
    ESTIMATE_ONLY
}
