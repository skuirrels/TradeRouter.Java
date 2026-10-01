package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class UnitConversionTests {

    @ParameterizedTest
    @CsvSource({
            "km, KM, 0.001",
            "m, METERS, 1.0",
            "mi, MILES, 0.000621371192",
            "ft, FEET, 3.28084",
            "in, INCHES, 39.370",
            "deg, DEGREES, 0.0000089827",
            "cen, CENTIMETERS, 100.0",
            "rad, RADIANS, 0.00000015696",
            "naut, NAUTICAL_MILES, 0.000539956803",
            "nm, NAUTICAL_MILES, 0.000539956803",
            "yd, YARDS, 1.0936132983377078"
    })
    void unitParsingAndFactorsShouldMatchExpected(String unitStr, DistanceUnit expectedUnit, double expectedFactor) {
        DistanceUnit parsed = DistanceUnit.parse(unitStr);
        assertThat(parsed).isEqualTo(expectedUnit);

        double factor = parsed.conversionFactorFromMeters();
        assertThat(factor).isCloseTo(expectedFactor, within(1e-6));
    }

    @Test
    void exactFactorsForDegreesAndRadians() {
        assertThat(DistanceUnit.DEGREES.conversionFactorFromMeters()).isEqualTo(1.0 / 111325.0);
        assertThat(DistanceUnit.RADIANS.conversionFactorFromMeters()).isEqualTo(1.0 / 6371008.8);
    }

    @ParameterizedTest
    @CsvSource({
            "KM, 1.852",
            "METERS, 1852.0",
            "MILES, 1.15078",
            "NAUTICAL_MILES, 1.0"
    })
    void speedCoefficientShouldConvertKnotsCorrectly(DistanceUnit unit, double expectedCoef) {
        assertThat(unit.speedCoefficient()).isCloseTo(expectedCoef, within(1e-4));
    }

    @Test
    void haversineDistanceKnownPointsShouldBeAccurate() {
        // London (0.1278 W, 51.5074 N) to Paris (2.3522 E, 48.8566 N) ~ 343 km
        Coordinate london = new Coordinate(-0.1278, 51.5074);
        Coordinate paris = new Coordinate(2.3522, 48.8566);

        assertThat(Haversine.distance(london, paris, DistanceUnit.KM)).isBetween(340.0, 350.0);
        assertThat(Haversine.distance(london, paris, DistanceUnit.NAUTICAL_MILES)).isBetween(180.0, 190.0);
        assertThat(Haversine.distance(london, paris, DistanceUnit.MILES)).isBetween(210.0, 220.0);
    }

    @Test
    void durationHoursCalculationShouldBeAccurate() {
        // 240 nautical miles at 24 knots should take exactly 10 hours
        double duration = Haversine.calculateDurationHours(24.0, 240.0, DistanceUnit.NAUTICAL_MILES);
        assertThat(duration).isCloseTo(10.0, within(1e-4));

        // 1852 km at 24 knots (~44.448 km/h) should take ~41.67 hours
        double kmDuration = Haversine.calculateDurationHours(24.0, 1852.0, DistanceUnit.KM);
        assertThat(kmDuration).isCloseTo(1852.0 / (24.0 * 1.852), within(1e-4));
    }

    @Test
    void haversineDistanceIdenticalCoordinatesReturnsZeroWithoutNaN() {
        Coordinate pt = new Coordinate(121.47, 31.23);
        double dist = Haversine.distance(pt, pt);
        assertThat(dist).isEqualTo(0.0);
        assertThat(Double.isNaN(dist)).isFalse();

        double distKm = Haversine.distanceKm(pt, pt);
        assertThat(distKm).isEqualTo(0.0);
        assertThat(Double.isNaN(distKm)).isFalse();
    }

    @Test
    void nullDistanceUnitIsRejectedByPublicConversionHelpers() {
        Coordinate a = new Coordinate(0, 0);
        Coordinate b = new Coordinate(1, 1);

        assertThatThrownBy(() -> Haversine.distance(a, b, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Haversine.calculatePathLength(List.of(a, b), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Haversine.calculateDurationHours(10.0, 10.0, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DistanceUnit.parse("furlongs")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void haversineDistanceRejectsInvalidCoordinates() {
        assertThatThrownBy(() -> Haversine.distanceKm(new Coordinate(0, 91), new Coordinate(0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @ParameterizedTest
    @EnumSource(DistanceUnit.class)
    void unitStringRoundTripsThroughParse(DistanceUnit unit) {
        assertThat(DistanceUnit.parse(unit.unitString())).isEqualTo(unit);
    }

    @ParameterizedTest
    @EnumSource(DistanceUnit.class)
    void speedCoefficientMatchesOneNauticalMileInTheSameUnit(DistanceUnit unit) {
        double oneNauticalMile = 1852.0 * unit.conversionFactorFromMeters();

        assertThat(unit.speedCoefficient()).isCloseTo(oneNauticalMile, within(oneNauticalMile * 1e-5));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "' Kilometers ', KM",
            "METER, METERS",
            "miles, MILES",
            "foot, FEET",
            "inches, INCHES",
            "degree, DEGREES",
            "cm, CENTIMETERS",
            "radians, RADIANS",
            "NauticalMiles, NAUTICAL_MILES",
            "nautical, NAUTICAL_MILES",
            "yard, YARDS",
            "null, KM",
            "'', KM",
            "'   ', KM"
    })
    void parseAcceptsAliasesAndDefaultsBlankToKilometres(String unitStr, DistanceUnit expected) {
        assertThat(DistanceUnit.parse(unitStr)).isEqualTo(expected);
    }

    @Test
    void parseRejectsUnknownUnitAndListsValidOnes() {
        assertThatThrownBy(() -> DistanceUnit.parse("furlong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Unsupported distance unit: 'furlong'. Valid units:");
    }

    @Test
    void optionsUnitStringReadsAndWritesUnits() {
        TradeRouterOptions options = new TradeRouterOptions().setUnitString("yd");

        assertThat(options.getUnits()).isEqualTo(DistanceUnit.YARDS);
        assertThat(options.getUnitString()).isEqualTo("yd");
        assertThatThrownBy(() -> options.setUnitString("furlong")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(DistanceUnit.class)
    void routeLengthAndDurationAreConsistentAcrossUnits(DistanceUnit unit) {
        Coordinate origin = new Coordinate(5.333333, 43.333333);
        Coordinate destination = new Coordinate(18.366667, -33.916667);
        GeoJsonFeature inKm = TradeRoutes.calculate(origin, destination, new TradeRouterOptions());

        GeoJsonFeature inUnit = TradeRoutes.calculate(origin, destination, new TradeRouterOptions().setUnits(unit));

        double expectedLength = inKm.properties().getLength() * 1000.0 * unit.conversionFactorFromMeters();
        assertThat(inUnit.properties().getUnits()).isEqualTo(unit.unitString());
        assertThat(inUnit.properties().getLength()).isCloseTo(expectedLength, within(expectedLength * 1e-9));
        assertThat(inUnit.properties().getDurationHours())
                .isCloseTo(inKm.properties().getDurationHours(), within(inKm.properties().getDurationHours() * 1e-5));
    }
}
