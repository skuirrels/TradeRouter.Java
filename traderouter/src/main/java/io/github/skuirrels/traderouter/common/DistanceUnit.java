package io.github.skuirrels.traderouter.common;

import java.util.Locale;
import java.util.Objects;

/**
 * Supported distance units for route length calculations.
 */
public enum DistanceUnit {
    /** Kilometres (km), the default. */
    KM("km", 0.001, 1.852),
    /** Metres (m). */
    METERS("m", 1.0, 1852.0),
    /** Statute miles (mi). */
    MILES("mi", 0.000621371192, 1.15078),
    /** Feet (ft). */
    FEET("ft", 3.28084, 6076.12),
    /** Inches (in). */
    INCHES("in", 39.370, 72913.4),
    /** Degrees (deg). */
    DEGREES("deg", 1.0 / 111325.0, 1852.0 / 111325.0),
    /** Centimetres (cen). */
    CENTIMETERS("cen", 100.0, 185200.0),
    /** Radians (rad). */
    RADIANS("rad", 1.0 / 6371008.8, 1852.0 / 6371008.8),
    /** Nautical miles (naut / nm). */
    NAUTICAL_MILES("naut", 0.000539956803, 1.0),
    /** Yards (yd). */
    YARDS("yd", 1.0936132983377078, 2025.37);

    private final String unitString;
    private final double conversionFactorFromMeters;
    private final double speedCoefficient;

    DistanceUnit(String unitString, double conversionFactorFromMeters, double speedCoefficient) {
        this.unitString = unitString;
        this.conversionFactorFromMeters = conversionFactorFromMeters;
        this.speedCoefficient = speedCoefficient;
    }

    /**
     * Converts a unit string (for example "km", "mi", "naut") to the corresponding unit. Null or blank input
     * yields {@link #KM}.
     */
    public static DistanceUnit parse(String unitStr) {
        if (unitStr == null || unitStr.isBlank()) {
            return KM;
        }
        return switch (unitStr.trim().toLowerCase(Locale.ROOT)) {
            case "km", "kilometer", "kilometers", "kilometre", "kilometres" -> KM;
            case "m", "meter", "meters", "metre", "metres" -> METERS;
            case "mi", "mile", "miles" -> MILES;
            case "ft", "foot", "feet" -> FEET;
            case "in", "inch", "inches" -> INCHES;
            case "deg", "degree", "degrees" -> DEGREES;
            case "cen", "cm", "centimeter", "centimeters", "centimetre", "centimetres" -> CENTIMETERS;
            case "rad", "radian", "radians" -> RADIANS;
            case "naut", "nm", "nautical", "nauticalmile", "nauticalmiles" -> NAUTICAL_MILES;
            case "yd", "yard", "yards" -> YARDS;
            default -> throw new IllegalArgumentException(
                    "Unsupported distance unit: '" + unitStr + "'. Valid units: km, m, mi, ft, in, deg, cen, rad, naut, yd.");
        };
    }

    /** The canonical string representation of the unit, for example "km" or "naut". */
    public String unitString() {
        return unitString;
    }

    /** Conversion factor from metres to this unit: {@code valueInUnit = metres * factor}. */
    public double conversionFactorFromMeters() {
        return conversionFactorFromMeters;
    }

    /**
     * Speed coefficient converting knots to this unit per hour:
     * {@code speedInUnitPerHour = speedInKnots * speedCoefficient()}.
     */
    public double speedCoefficient() {
        return speedCoefficient;
    }

    /** Rejects a null unit with a descriptive message. */
    static DistanceUnit requireUnit(DistanceUnit unit) {
        return Objects.requireNonNull(unit, "Distance unit cannot be null.");
    }
}
