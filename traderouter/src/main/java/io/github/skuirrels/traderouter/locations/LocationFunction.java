package io.github.skuirrels.traderouter.locations;

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
    public static final Set<LocationFunction> NONE = Set.of();

    private static final Set<LocationFunction>[] BY_FLAGS = buildFlagSets();

    private final int bit;

    LocationFunction(int bit) {
        this.bit = bit;
    }

    /** The bit this function occupies in the embedded flag mask. */
    public int bit() {
        return bit;
    }

    /**
     * Converts a flag mask to an immutable set of functions. Bits outside the known functions are ignored. The
     * 256 possible sets are shared, so the embedded list does not allocate one per entry.
     */
    public static Set<LocationFunction> fromFlags(int flags) {
        return BY_FLAGS[flags & 0xFF];
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<LocationFunction>[] buildFlagSets() {
        Set<LocationFunction>[] sets = new Set[256];
        for (int flags = 0; flags < sets.length; flags++) {
            EnumSet<LocationFunction> result = EnumSet.noneOf(LocationFunction.class);
            for (LocationFunction function : values()) {
                if ((flags & function.bit) != 0) {
                    result.add(function);
                }
            }
            sets[flags] = Set.copyOf(result);
        }
        return sets;
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
        return fromFlags(toFlags(EnumSet.of(first, rest)));
    }

    /** Lower-case identifier, for example "sea_port". */
    public String identifier() {
        return name().toLowerCase(Locale.ROOT);
    }
}
