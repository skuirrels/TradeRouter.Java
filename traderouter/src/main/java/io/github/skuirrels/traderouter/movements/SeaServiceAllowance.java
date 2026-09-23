package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.passages.Passage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * The operational allowance a sea leg receives on top of its travelling time, chosen by trade corridor.
 *
 * <p>A shortest-path route sails straight from load port to discharge port. A scheduled container service
 * does not: it calls at several ports on the way, slows in restricted water and waits for berths. The
 * allowance is the fraction of travelling time that covers those things. One fraction cannot fit every trade,
 * because Asia–Europe loops make many intermediate calls while transpacific services sail almost direct and
 * faster.
 *
 * <p>The fractions below were fitted on 12 September 2026 against observed port-to-port sailings, berth
 * departure to berth arrival, for legs departing in 2025 and 2026, from a freight forwarder's operational
 * data warehouse: eleven direct lanes, 8,600 sailings, each fraction the median of observed hours divided by
 * this library's travelling hours at 16 knots, minus one. Corridors without observations keep the historical
 * default of 0.20. Details are recorded in DATA_PROVENANCE.md.
 */
public final class SeaServiceAllowance {
    private SeaServiceAllowance() {
    }

    /** Fraction applied where no corridor has been fitted, the value every leg used before calibration. */
    public static final double DEFAULT_FRACTION = 0.20;

    /** Corridor label reported when the request fixes its own fraction. */
    public static final String OVERRIDE_CORRIDOR = "override";

    /** Corridor label reported when no fitted corridor matches. */
    public static final String DEFAULT_CORRIDOR = "default";

    /** Fitted corridor fractions, keyed by the label reported on each leg. */
    public static final Map<String, Double> FRACTIONS;

    static {
        Map<String, Double> fractions = new LinkedHashMap<>();
        fractions.put("asia-north-europe", 0.63);   // Malacca or Sunda, Suez and Gibraltar: CNSHA–NLRTM, CNSHA–DEHAM, CNSHA–FRLEH, VNSGN–NLRTM
        fractions.put("asia-mediterranean", 1.33);  // Malacca or Sunda and Suez, no Gibraltar: CNSHA–ITGOA, CNSHA–GRPIR
        fractions.put("gulf-europe", 1.57);         // Hormuz and Suez: AEJEA–NLRTM
        fractions.put("transpacific", 0.12);        // no passage, East Asia to the Americas: CNSHA–USLAX, VNSGN–USLAX
        fractions.put("panama", 0.24);              // Panama: CNSHA–USNYC
        fractions.put("transatlantic", 0.80);       // no passage, Europe or Africa to the Americas: NLRTM–USNYC
        fractions.put("asia-north-europe-cape", 0.22);  // no Suez, Asia to North Europe: the four asia-north-europe lanes on the Cape route
        fractions.put("asia-mediterranean-cape", 0.37); // no Suez, Asia to the Mediterranean: the two asia-mediterranean lanes on the Cape route
        fractions.put("gulf-europe-cape", 0.46);        // no Suez, Hormuz and the Cape: AEJEA–NLRTM on the Cape route
        fractions.put(DEFAULT_CORRIDOR, DEFAULT_FRACTION);
        FRACTIONS = Collections.unmodifiableMap(fractions);
    }

    /**
     * Chooses the corridor and fraction for a sea leg from the passages it crosses and its end points.
     *
     * @param traversedPassages passages the routed leg crosses, as reported by the router; may be null
     * @param from              start of the leg
     * @param to                end of the leg
     * @return the corridor label and the fraction of travelling time to add
     */
    public static CorridorAllowance resolve(Iterable<String> traversedPassages, Coordinate from, Coordinate to) {
        Set<String> passages = new HashSet<>();
        if (traversedPassages != null) {
            for (String passage : traversedPassages) {
                if (passage != null) {
                    passages.add(passage.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        boolean suez = passages.contains(Passage.SUEZ);
        boolean eastOfIndia = passages.contains(Passage.MALACCA) || passages.contains(Passage.SUNDA);
        double lonFrom = from.withNormalizedLongitude().longitude();
        double lonTo = to.withNormalizedLongitude().longitude();

        boolean panama = passages.contains(Passage.PANAMA);
        boolean americanEnd = inAmericas(lonFrom) || inAmericas(lonTo);
        boolean europeanEnd = inEurope(lonFrom, from.latitude()) || inEurope(lonTo, to.latitude());
        boolean eastAsianEnd = lonFrom >= 90.0 || lonTo >= 90.0;

        String corridor;
        if (panama && americanEnd) {
            corridor = "panama";
        } else if (suez && passages.contains(Passage.ORMUZ)) {
            corridor = "gulf-europe";
        } else if (suez && eastOfIndia) {
            // Through Gibraltar the leg ends in North Europe, or beyond it in the Americas, which is unfitted.
            corridor = passages.contains(Passage.GIBRALTAR)
                    ? (americanEnd ? DEFAULT_CORRIDOR : "asia-north-europe")
                    : "asia-mediterranean";
        } else if (!suez && europeanEnd && passages.contains(Passage.SOUTH_AFRICA) && passages.contains(Passage.ORMUZ)) {
            corridor = "gulf-europe-cape";
        } else if (!suez && europeanEnd && !americanEnd
                && ((eastOfIndia && passages.contains(Passage.SOUTH_AFRICA)) || (panama && eastAsianEnd))) {
            // Asia–Europe services that avoid Suez sail round the Cape. A leg the router sends through Panama,
            // because the caller closed Suez but not Panama, is a similar distance and takes the same corridor.
            // Round the Cape only the Mediterranean is reached through Gibraltar.
            corridor = passages.contains(Passage.GIBRALTAR) ? "asia-mediterranean-cape" : "asia-north-europe-cape";
        } else if (!suez && crossesPacific(lonFrom, lonTo)) {
            corridor = "transpacific";
        } else if (!suez && crossesAtlantic(lonFrom, lonTo)) {
            corridor = "transatlantic";
        } else {
            corridor = DEFAULT_CORRIDOR;
        }

        return new CorridorAllowance(corridor, FRACTIONS.get(corridor));
    }

    // East Asia sits east of 90°E, the American Pacific coast west of 90°W.
    private static boolean crossesPacific(double lonA, double lonB) {
        return (lonA >= 90.0 && lonB <= -90.0) || (lonB >= 90.0 && lonA <= -90.0);
    }

    // Europe and Africa lie between 30°W and 60°E; the Americas west of 30°W. No passage separates them.
    private static boolean crossesAtlantic(double lonA, double lonB) {
        return (inEuropeOrAfrica(lonA) && inAmericas(lonB)) || (inEuropeOrAfrica(lonB) && inAmericas(lonA));
    }

    private static boolean inEuropeOrAfrica(double lon) {
        return lon >= -30.0 && lon <= 60.0;
    }

    // Europe, including the Mediterranean and Black Sea coasts, lies north of 30°N between 30°W and 42°E.
    // The bound keeps West Africa, the Red Sea and the Gulf out.
    private static boolean inEurope(double lon, double lat) {
        return lat >= 30.0 && lon >= -30.0 && lon <= 42.0;
    }

    private static boolean inAmericas(double lon) {
        return lon < -30.0 && lon > -170.0;
    }
}
