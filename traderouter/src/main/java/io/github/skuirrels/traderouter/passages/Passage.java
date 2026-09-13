package io.github.skuirrels.traderouter.passages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Known maritime passages, straits and canals that can be dynamically restricted or avoided.
 */
public final class Passage {
    private Passage() {
    }

    /** Bab-el-Mandeb strait between Yemen and Djibouti. */
    public static final String BABALMANDAB = "babalmandab";

    /** Bosporus strait in Turkey. */
    public static final String BOSPORUS = "bosporus";

    /** Strait of Gibraltar between Spain and Morocco. */
    public static final String GIBRALTAR = "gibraltar";

    /** Suez Canal in Egypt. */
    public static final String SUEZ = "suez";

    /** Panama Canal in Panama. */
    public static final String PANAMA = "panama";

    /** Strait of Hormuz between Oman/UAE and Iran. */
    public static final String ORMUZ = "ormuz";

    /** Northwest Passage through the Arctic Ocean. */
    public static final String NORTHWEST = "northwest";

    /** Strait of Malacca between Malaysia/Singapore and Indonesia. */
    public static final String MALACCA = "malacca";

    /** Sunda Strait in Indonesia. */
    public static final String SUNDA = "sunda";

    /** Chilean channels and Magellan Strait. */
    public static final String CHILI = "chili";

    /** Cape of Good Hope / South Africa route. */
    public static final String SOUTH_AFRICA = "south_africa";

    /** Bering Strait between Russia and Alaska. */
    public static final String BERING = "bering";

    /** Dardanelles strait connecting the Aegean Sea to the Sea of Marmara. */
    public static final String DARDANELLES = "dardanelles";

    /** All recognized passage identifiers, in lower case. */
    public static final Set<String> VALID_PASSAGES = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            BABALMANDAB, BOSPORUS, GIBRALTAR, SUEZ, PANAMA, ORMUZ, NORTHWEST, MALACCA, SUNDA, CHILI, SOUTH_AFRICA,
            BERING, DARDANELLES)));

    /** Whether the identifier names a recognized passage, matched case-insensitively after trimming. */
    public static boolean isValid(String passage) {
        return passage != null && VALID_PASSAGES.contains(passage.trim().toLowerCase(Locale.ROOT));
    }

    /** Returns a human-readable name for a recognized passage identifier. */
    public static String getDisplayName(String passage) {
        if (passage == null || passage.isBlank()) {
            throw new IllegalArgumentException("Passage identifier cannot be null or blank.");
        }
        return switch (passage.trim().toLowerCase(Locale.ROOT)) {
            case BABALMANDAB -> "Bab-el-Mandeb";
            case BOSPORUS -> "Bosporus";
            case GIBRALTAR -> "Gibraltar";
            case SUEZ -> "Suez";
            case PANAMA -> "Panama";
            case ORMUZ -> "Hormuz";
            case NORTHWEST -> "Northwest Passage";
            case MALACCA -> "Malacca";
            case SUNDA -> "Sunda";
            case CHILI -> "Magellan Strait";
            case SOUTH_AFRICA -> "Cape of Good Hope";
            case BERING -> "Bering Strait";
            case DARDANELLES -> "Dardanelles";
            default -> throw new IllegalArgumentException("Unknown passage identifier '" + passage + "'.");
        };
    }

    /** Filters and returns only recognized passage names from the candidate list, lower-cased and deduplicated. */
    public static List<String> filterValidPassages(Iterable<String> candidates) {
        if (candidates == null) {
            return new ArrayList<>();
        }

        Set<String> result = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }

            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            if (VALID_PASSAGES.contains(normalized)) {
                result.add(normalized);
            }
        }

        return new ArrayList<>(result);
    }
}
