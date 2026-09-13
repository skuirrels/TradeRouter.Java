package io.github.skuirrels.traderouter.movements;

import java.util.List;
import java.util.Optional;

/**
 * Role of a leg within a movement.
 */
public enum LegKind {
    /** Main carriage, typically port to port. */
    MAIN("main", "Main", List.of("main")),
    /** Pre-carriage from the pickup place to the port of loading. */
    PICKUP("pickup", "Pickup", List.of("pickup", "collection")),
    /** On-carriage from the port of discharge to the delivery place. */
    DELIVERY("delivery", "Delivery", List.of("delivery"));

    private final String wireString;
    private final String displayName;
    private final List<String> spellings;

    LegKind(String wireString, String displayName, List<String> spellings) {
        this.wireString = wireString;
        this.displayName = displayName;
        this.spellings = spellings;
    }

    /** Canonical lower-case identifier written to GeoJSON. */
    public String wireString() {
        return wireString;
    }

    /** Capitalised name used in text reports, for example "Pickup". */
    public String displayName() {
        return displayName;
    }

    /** Accepted spellings of the kind in leg lines, canonical form first. */
    public List<String> spellings() {
        return spellings;
    }

    /** Parses a kind word, case-insensitively, accepting the spellings in {@link #spellings()}. */
    public static Optional<LegKind> tryParse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        for (LegKind candidate : values()) {
            for (String spelling : candidate.spellings) {
                if (trimmed.equalsIgnoreCase(spelling)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
