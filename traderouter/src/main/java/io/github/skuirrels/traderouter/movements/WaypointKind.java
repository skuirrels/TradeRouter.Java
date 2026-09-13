package io.github.skuirrels.traderouter.movements;

/**
 * Semantic type declared for a movement endpoint.
 */
public enum WaypointKind {
    /** No endpoint type was declared. */
    UNSPECIFIED("Unspecified"),
    /** A general place with no required transport function. */
    PLACE("Place"),
    /** A maritime port. */
    PORT("Port"),
    /** An airport. */
    AIRPORT("Airport"),
    /** A rail station or terminal. */
    STATION("Station"),
    /** A transport terminal of any recorded terminal type. */
    TERMINAL("Terminal"),
    /** A depot or multimodal facility. */
    DEPOT("Depot");

    private final String displayName;

    WaypointKind(String displayName) {
        this.displayName = displayName;
    }

    /** Capitalised name used in messages, for example "Port". */
    public String displayName() {
        return displayName;
    }
}
