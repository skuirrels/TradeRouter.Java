package io.github.skuirrels.traderouter.caching;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.movements.RoadRouteRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Identifies one cached road route: a caller-chosen partition and the two endpoint coordinates, with longitude
 * normalised to [-180, 180) and both rounded, so that the same place resolved twice maps to the same entry. The leg
 * sequence and the endpoints' codes and names are not part of the key, because a road-route provider routes between
 * coordinates.
 *
 * <p>{@link #value()} is the same string TradeRouter.Net builds, so Java and .NET services can share one store.
 */
public final class RoadRouteCacheKey {
    // The powers of ten .NET Math.Round scales by, all exact doubles.
    private static final double[] POWERS_OF_TEN = {
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10
    };

    private final String partition;
    private final Coordinate from;
    private final Coordinate to;
    private final String value;

    private RoadRouteCacheKey(String partition, Coordinate from, Coordinate to, int coordinateDecimals) {
        this.partition = partition;
        this.from = from;
        this.to = to;
        this.value = partition
                + "|" + format(from.longitude(), coordinateDecimals) + "," + format(from.latitude(), coordinateDecimals)
                + "|" + format(to.longitude(), coordinateDecimals) + "," + format(to.latitude(), coordinateDecimals);
    }

    /**
     * Builds the key for a road-route request.
     *
     * @param partition          name for the provider, profile and road dataset, for example
     *                           {@code osrm-driving-europe-261001}. Change it when any of them changes, so routes
     *                           from the old setup are no longer served. Surrounding whitespace is removed.
     * @param request            the road-route request
     * @param coordinateDecimals decimal places the endpoint coordinates are rounded to, from 0 to 10
     * @throws IllegalArgumentException when the partition is blank or contains {@code |}, the decimals are out of
     *                                  range, or an endpoint is not a valid coordinate
     */
    public static RoadRouteCacheKey create(String partition, RoadRouteRequest request, int coordinateDecimals) {
        validatePartition(partition);
        validateCoordinateDecimals(coordinateDecimals);
        return createValidated(partition.trim(), request, coordinateDecimals);
    }

    // For callers that validated and trimmed the partition and decimals once, up front.
    static RoadRouteCacheKey createValidated(String partition, RoadRouteRequest request, int coordinateDecimals) {
        Objects.requireNonNull(request, "request");
        request.from().coordinate().validate();
        request.to().coordinate().validate();

        return new RoadRouteCacheKey(
                partition,
                round(request.from().coordinate(), coordinateDecimals),
                round(request.to().coordinate(), coordinateDecimals),
                coordinateDecimals);
    }

    /** Caller-chosen name for the provider, profile and road dataset the route came from. */
    public String partition() {
        return partition;
    }

    /** Normalised and rounded road origin. */
    public Coordinate from() {
        return from;
    }

    /** Normalised and rounded road destination. */
    public Coordinate to() {
        return to;
    }

    /** The key as one string, {@code partition|lon,lat|lon,lat} with a full stop as the decimal separator. */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RoadRouteCacheKey key
                && partition.equals(key.partition)
                && from.equals(key.from)
                && to.equals(key.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(partition, from, to);
    }

    @Override
    public String toString() {
        return value;
    }

    static void validatePartition(String partition) {
        if (partition == null || partition.isBlank()) {
            throw new IllegalArgumentException("The cache partition cannot be blank.");
        }
        if (partition.indexOf('|') >= 0) {
            throw new IllegalArgumentException("The cache partition cannot contain '|', which separates the parts of a key.");
        }
    }

    static void validateCoordinateDecimals(int coordinateDecimals) {
        if (coordinateDecimals < 0 || coordinateDecimals > 10) {
            throw new IllegalArgumentException(
                    "Coordinates can be rounded to between 0 and 10 decimal places. Received: " + coordinateDecimals);
        }
    }

    // Longitude is normalised before rounding, which absorbs the normaliser's floating-point noise, and a rounded
    // 180 becomes -180, so the same meridian always gives the same key. Adding 0.0 turns a rounded -0 into 0, so a
    // coordinate just below zero shares the entry of one just above.
    private static Coordinate round(Coordinate coordinate, int decimals) {
        double longitude = roundAwayFromZero(coordinate.withNormalizedLongitude().longitude(), decimals);
        if (longitude >= 180.0) {
            longitude -= 360.0;
        }

        return new Coordinate(longitude + 0.0, roundAwayFromZero(coordinate.latitude(), decimals) + 0.0);
    }

    // Mirrors .NET Math.Round(value, digits, MidpointRounding.AwayFromZero): scale by a power of ten, round the
    // scaled value half away from zero, and scale back. Rounding the exact binary value instead can differ when the
    // scaling multiplication itself rounds onto or off a midpoint, and the key must match .NET's.
    static double roundAwayFromZero(double value, int decimals) {
        double power = POWERS_OF_TEN[decimals];
        double scaled = value * power;
        double whole = scaled < 0.0 ? Math.ceil(scaled) : Math.floor(scaled);
        double fraction = scaled - whole;
        if (Math.abs(fraction) >= 0.5) {
            whole += Math.signum(fraction);
        }
        return whole / power;
    }

    // Formats a rounded coordinate as .NET's shortest round-trip "R" format does. The value is the double nearest a
    // decimal with at most the given number of places, so that decimal, without trailing zeros, is the shortest
    // string that reads back as the same double. .NET switches to exponent notation below 1E-04, as in "1E-05".
    static String format(double value, int decimals) {
        if (value == 0.0) {
            return "0";
        }
        BigDecimal decimal = new BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros();
        if (Math.abs(value) >= 1e-4) {
            return decimal.toPlainString();
        }

        String digits = decimal.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - decimal.scale();
        StringBuilder text = new StringBuilder(16);
        if (decimal.signum() < 0) {
            text.append('-');
        }
        text.append(digits.charAt(0));
        if (digits.length() > 1) {
            text.append('.').append(digits, 1, digits.length());
        }
        text.append("E-");
        if (-exponent < 10) {
            text.append('0');
        }
        return text.append(-exponent).toString();
    }
}
