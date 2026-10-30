package io.github.skuirrels.traderouter.movements;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Counts the events on a rail leg that the optional rail time allowances on {@link MovementRequest} are charged
 * for: changes between Class I railroads along the routed path and crossings of the US–Canada or US–Mexico border.
 *
 * <p>A rail leg's travelling time is distance divided by an average speed. Real carload and intermodal moves also
 * wait at the origin and destination terminals, are handed between railroads and are held for customs at the
 * border. Those allowances default to zero, so the travelling time is unchanged unless the request sets them.
 * Sources and suggested values are recorded in DATA_PROVENANCE.md.
 */
public final class RailServiceAllowance {
    private RailServiceAllowance() {
    }

    /**
     * Reporting marks of the Class I railroads in the embedded rail network: the six the Association of American
     * Railroads lists since Canadian Pacific and Kansas City Southern merged as CPKC in 2023. Ferromex (FXE) is not
     * a Class I railroad and is not counted.
     */
    public static final Set<String> CLASS_I_RAILROADS = Set.of("BNSF", "CN", "CPKC", "CSXT", "NS", "UP");

    /** Countries whose shared borders are counted as rail border crossings, by ISO 3166-1 code. */
    public static final Set<String> BORDER_COUNTRIES = Set.of("CA", "MX", "US");

    /**
     * Track a Class I railroad must own along a routed leg, in kilometres, for the leg to be counted as running on it.
     */
    public static final double MINIMUM_CLASS_I_DISTANCE_KM = 1_000.0;

    /**
     * One segment of a routed rail path.
     *
     * @param owner reporting mark of the railroad owning the track, or null when unknown
     * @param km    length of the segment in kilometres
     */
    public record TrackSegment(String owner, double km) {
    }

    /**
     * Estimates the interchanges on a routed rail leg: the number of Class I railroads owning at least
     * {@link #MINIMUM_CLASS_I_DISTANCE_KM} of its track, less one.
     *
     * <p>The rail network records who owns the track, not who runs the train. The shortest path switches between
     * parallel lines, such as BNSF's and Union Pacific's between Los Angeles and Chicago, and crosses short pieces of
     * terminal, switching and short-line track, though one railroad runs the whole way. Counting every change of
     * owner would charge Los Angeles to Chicago with five interchanges where a shipper sees none. Counting only
     * railroads that own a long stretch of the route keeps the hand-offs that matter most, the long hauls that pass
     * from a western to an eastern railroad at Chicago, St Louis, Memphis or New Orleans. It misses interchanges on
     * shorter legs and counts one where a railroad runs over another's long line by trackage rights. Set
     * {@link MovementRequest#getRailInterchangeCounts()} when the actual count is known.
     *
     * @param segments track owner and length of each segment of the routed path; order does not matter
     * @return the estimated number of interchanges, never negative
     */
    public static int countClassIInterchanges(Iterable<TrackSegment> segments) {
        Objects.requireNonNull(segments, "segments");

        Map<String, Double> kmByRailroad = new HashMap<>();
        for (TrackSegment segment : segments) {
            Objects.requireNonNull(segment, "segments must not contain null");
            String owner = segment.owner();
            if (owner != null && CLASS_I_RAILROADS.contains(owner)) {
                kmByRailroad.merge(owner, segment.km(), Double::sum);
            }
        }
        int railroads = 0;
        for (double km : kmByRailroad.values()) {
            if (km >= MINIMUM_CLASS_I_DISTANCE_KM) {
                railroads++;
            }
        }
        return Math.max(0, railroads - 1);
    }

    /**
     * Counts the border crossings of a rail leg from the countries of its ends: one between the United States and
     * Canada or Mexico, two between Canada and Mexico, which no rail line joins except through the United States.
     *
     * <p>The route between the ends is not examined, so a leg between two places in one country that runs through
     * another, such as Detroit to Buffalo through southern Ontario, is counted as no crossing.
     *
     * @param fromCountry ISO 3166-1 code of the start of the leg, or null when unknown
     * @param toCountry   ISO 3166-1 code of the end of the leg, or null when unknown
     * @return the number of crossings, zero when either country is unknown or outside North America
     */
    public static int countBorderCrossings(String fromCountry, String toCountry) {
        if (fromCountry == null || toCountry == null
                || !BORDER_COUNTRIES.contains(fromCountry) || !BORDER_COUNTRIES.contains(toCountry)
                || fromCountry.equals(toCountry)) {
            return 0;
        }
        return fromCountry.equals("US") || toCountry.equals("US") ? 1 : 2;
    }
}
