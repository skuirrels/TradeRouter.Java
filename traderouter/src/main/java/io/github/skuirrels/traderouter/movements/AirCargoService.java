package io.github.skuirrels.traderouter.movements;

/** How an air leg's cargo flies, which sets its GLEC emission intensity. */
public enum AirCargoService {
    /** Not known: GLEC's weighted average of 55% belly freight and 45% freighter. */
    UNKNOWN("unknown"),
    /** On an all-cargo aircraft. */
    FREIGHTER("freighter"),
    /** In the hold of a passenger aircraft. */
    BELLY("belly");

    private final String wireString;

    AirCargoService(String wireString) {
        this.wireString = wireString;
    }

    /** Canonical lower-case identifier written to GeoJSON as {@code air_service}. */
    public String wireString() {
        return wireString;
    }
}
