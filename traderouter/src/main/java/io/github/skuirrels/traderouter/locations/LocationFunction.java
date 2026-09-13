package io.github.skuirrels.traderouter.locations;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * A function a UN/LOCODE location performs, as flagged in the UNECE code list. Locations usually carry several,
 * held as a {@link Set}; {@link #fromFlags(int)} and {@link #toFlags(Set)} convert to and from the bit mask
 * stored in the embedded data.
 */
public enum LocationFunction {
    /** Sea port. */
    SEA_PORT(1),
    /** Rail terminal. */
    RAIL_TERMINAL(2),
    /** Road terminal. */
    ROAD_TERMINAL(4),
    /** Airport. */
    AIRPORT(8),
    /** Postal exchange office. */
    POSTAL_EXCHANGE(16),
    /** Multimodal facility or inland container depot. */
    MULTIMODAL(32),
    /** Fixed transport such as a pipeline or oil platform. */
    FIXED_TRANSPORT(64),
    /** Border crossing. */
    BORDER_CROSSING(128);

    /** The empty function set: no function recorded. */
    public static final Set<LocationFunction> NONE = Collections.unmodifiableSet(EnumSet.noneOf(LocationFunction.class));

    private final int bit;

    LocationFunction(int bit) {
        this.bit = bit;
    }

    /** The bit this function occupies in the embedded flag mask. */
    public int bit() {
        return bit;
    }

    /** Converts a flag mask to an immutable set of functions. */
    public static Set<LocationFunction> fromFlags(int flags) {
        EnumSet<LocationFunction> result = EnumSet.noneOf(LocationFunction.class);
        for (LocationFunction function : values()) {
            if ((flags & function.bit) != 0) {
                result.add(function);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Converts a set of functions to its flag mask. */
    public static int toFlags(Set<LocationFunction> functions) {
        int flags = 0;
        if (functions != null) {
            for (LocationFunction function : functions) {
                flags |= function.bit;
            }
        }
        return flags;
    }

    /** Returns an immutable set of the given functions. */
    public static Set<LocationFunction> of(LocationFunction first, LocationFunction... rest) {
        return Collections.unmodifiableSet(EnumSet.of(first, rest));
    }

    /** Lower-case identifier, for example "sea_port". */
    public String identifier() {
        return name().toLowerCase(Locale.ROOT);
    }
}
