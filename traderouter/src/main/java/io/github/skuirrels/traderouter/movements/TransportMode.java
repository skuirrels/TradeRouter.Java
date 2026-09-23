package io.github.skuirrels.traderouter.movements;

import java.util.List;
import java.util.Optional;

/**
 * Transport mode of a movement leg. Only {@link #SEA} is routed on the maritime network; road uses
 * provider-routed or estimated distance, while rail and air use great-circle distance. The wire string is the single source of
 * truth for how modes are written in leg lines and in GeoJSON.
 */
public enum TransportMode {
    /** Ocean or coastal shipping, routed on the Marnet graph. */
    SEA("sea", "Sea", List.of("sea", "ocean", "vessel", "ship")),
    /** Road haulage, resolved by a road-network provider or fallback estimator. */
    ROAD("road", "Road", List.of("road", "truck", "lorry")),
    /** Rail, straight-line leg. */
    RAIL("rail", "Rail", List.of("rail", "train")),
    /** Air freight, straight-line leg. */
    AIR("air", "Air", List.of("air", "flight", "plane"));

    private final String wireString;
    private final String displayName;
    private final List<String> spellings;

    TransportMode(String wireString, String displayName, List<String> spellings) {
        this.wireString = wireString;
        this.displayName = displayName;
        this.spellings = spellings;
    }

    /** Canonical lower-case identifier written to GeoJSON. */
    public String wireString() {
        return wireString;
    }

    /** Capitalised name used in text reports, for example "Sea". */
    public String displayName() {
        return displayName;
    }

    /** Accepted spellings of the mode in leg lines, canonical form first. */
    public List<String> spellings() {
        return spellings;
    }

    /** Parses a mode word, case-insensitively, accepting the spellings in {@link #spellings()}. */
    public static Optional<TransportMode> tryParse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        for (TransportMode candidate : values()) {
            for (String spelling : candidate.spellings) {
                if (trimmed.equalsIgnoreCase(spelling)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
