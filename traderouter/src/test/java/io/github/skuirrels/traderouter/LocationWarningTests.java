package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.movements.LegKind;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.Location;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.RoadDurationMode;
import io.github.skuirrels.traderouter.movements.RoadRoutingMode;
import io.github.skuirrels.traderouter.movements.SuppliedRoute;
import io.github.skuirrels.traderouter.movements.TransportMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocationWarningTests {
    private static final Coordinate SHENZHEN_CITY = new Coordinate(114.06, 22.54);
    private static final Coordinate ALPS = new Coordinate(10.0, 46.5);
    private static final Coordinate PLACEHOLDER = new Coordinate(0.0, 0.0);

    private static MovementRequest pickup(Location pickup) {
        return new MovementRequest(List.of(
                new MovementLeg(pickup, Location.fromCode("CNSHG"), TransportMode.ROAD, LegKind.PICKUP),
                new MovementLeg("CNSHG", "SGSIN", TransportMode.SEA)))
                .setRoadRoutingMode(RoadRoutingMode.ESTIMATE_ONLY);
    }

    private static LegResult firstLeg(MovementRequest request) {
        return DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs().get(0);
    }

    private static Coordinate located(String code) {
        return DefaultTradeRouterEngine.getDefault().locate(code).coordinate();
    }

    @Test
    void coordinatesNearTheCodeCarryNoLocationWarning() {
        LegResult leg = firstLeg(pickup(Location.fromCode("CNSZX", SHENZHEN_CITY)));

        assertThat(leg.feature().properties().getLocationWarning()).isNull();
        assertThat(leg.from().coordinate()).isEqualTo(SHENZHEN_CITY);
    }

    @Test
    void coordinatesFarFromTheCodeAreUsedWithALocationWarning() {
        LegResult leg = firstLeg(pickup(Location.fromCode("CNSZX", ALPS)));

        assertThat(leg.from().coordinate()).isEqualTo(ALPS);
        assertThat(leg.from().label()).isEqualTo("CNSZX");
        assertThat(leg.feature().properties().getLocationWarning())
                .startsWith("Coordinates given for CNSZX lie ")
                .contains("km from its listed position")
                .contains("beyond the 50 km tolerance");
    }

    @Test
    void locationWarningIsPrintedInTheTextReport() {
        String text = DefaultTradeRouterEngine.getDefault().calculateMovement(pickup(Location.fromCode("CNSZX", ALPS))).toText();

        assertThat(text).contains("Leg 1 location = Coordinates given for CNSZX lie ");
        assertThat(text).doesNotContain("Leg 2 location");
    }

    @Test
    void locationWarningIsSerialisedAfterTheDistanceWarning() {
        String json = DefaultTradeRouterEngine.getDefault().calculateMovement(pickup(Location.fromCode("CNSZX", ALPS)))
                .toFeatureCollection().toJson();

        assertThat(json).contains("\"location_warning\":\"Coordinates given for CNSZX lie ");
        assertThat(json.indexOf("\"location_warning\"")).isGreaterThan(json.indexOf("\"distance_warning\""));
    }

    @Test
    void coordinateCheckCanBeTurnedOff() {
        MovementRequest request = pickup(Location.fromCode("CNSZX", ALPS)).setCoordinateCodeToleranceKm(Double.POSITIVE_INFINITY);

        assertThat(firstLeg(request).feature().properties().getLocationWarning()).isNull();
    }

    @Test
    void coordinateCheckUsesTheConfiguredTolerance() {
        MovementRequest request = pickup(Location.fromCode("CNSZX", ALPS)).setCoordinateCodeToleranceKm(20_000.0);

        assertThat(firstLeg(request).feature().properties().getLocationWarning()).isNull();
    }

    @Test
    void customCodeWithCoordinatesIsNotChecked() {
        LegResult leg = firstLeg(pickup(Location.fromCode("SITE1", SHENZHEN_CITY)));

        assertThat(leg.feature().properties().getLocationWarning()).isNull();
        assertThat(leg.from().coordinate()).isEqualTo(SHENZHEN_CITY);
    }

    @Test
    void placeholderCoordinatesWithACodeAreIgnoredInFavourOfTheCode() {
        LegResult leg = firstLeg(pickup(Location.fromCode("CNSZX", PLACEHOLDER, "Customer site")));

        assertThat(leg.from().coordinate()).isEqualTo(located("CNSZX"));
        assertThat(leg.from().name()).isEqualTo("Customer site");
        assertThat(leg.feature().properties().getLocationWarning())
                .isEqualTo("Coordinates (0, 0) given for CNSZX are a placeholder and were ignored; the code's own position was used.");
        // The road leg is estimated from Shenzhen, not from the Atlantic.
        assertThat(leg.length()).isLessThan(2_000.0);
    }

    @Test
    void placeholderCoordinatesWithoutACodeAreUsedWithAWarning() {
        LegResult leg = firstLeg(pickup(Location.fromCoordinate(PLACEHOLDER, "Customer site")));

        assertThat(leg.from().coordinate()).isEqualTo(PLACEHOLDER);
        assertThat(leg.feature().properties().getLocationWarning())
                .isEqualTo("Coordinates (0, 0) given for Customer site are usually a placeholder for a missing position; they were used as given.");
    }

    @Test
    void placeholderCoordinatesWithAnUnknownCodeAreUsedWithAWarning() {
        LegResult leg = firstLeg(pickup(Location.fromCode("ZZZZZ", PLACEHOLDER)));

        assertThat(leg.from().coordinate()).isEqualTo(PLACEHOLDER);
        assertThat(leg.from().label()).isEqualTo("ZZZZZ");
        assertThat(leg.feature().properties().getLocationWarning())
                .startsWith("Coordinates (0, 0) given for ZZZZZ are usually a placeholder, but ZZZZZ has no listed position");
    }

    @Test
    void placeholderCoordinateOverrideIsIgnoredWithAWarning() {
        MovementRequest request = pickup(Location.fromCode("CNSZX")).putCoordinate("CNSZX", PLACEHOLDER);

        LegResult leg = firstLeg(request);

        assertThat(leg.from().coordinate()).isEqualTo(located("CNSZX"));
        assertThat(leg.feature().properties().getLocationWarning())
                .contains("MovementRequest.getCoordinates() gives (0, 0) for CNSZX");
    }

    @Test
    void placeholderOverrideForAnUnknownCodeIsUsedAsGivenWithAWarning() {
        MovementRequest request = pickup(Location.fromCode("SITE1")).putCoordinate("SITE1", PLACEHOLDER);

        LegResult leg = firstLeg(request);

        assertThat(leg.from().coordinate()).isEqualTo(PLACEHOLDER);
        assertThat(leg.feature().properties().getLocationWarning())
                .isEqualTo("MovementRequest.getCoordinates() gives (0, 0) for SITE1, usually a placeholder, but SITE1 has no listed position to use instead, so it was used as given.");
    }

    @Test
    void placeholderCoordinatesWithAnOverrideUseTheOverrideAndSaySo() {
        MovementRequest request = pickup(Location.fromCode("CNSZX", PLACEHOLDER)).putCoordinate("CNSZX", SHENZHEN_CITY);

        LegResult leg = firstLeg(request);

        assertThat(leg.from().coordinate()).isEqualTo(SHENZHEN_CITY);
        assertThat(leg.feature().properties().getLocationWarning())
                .isEqualTo("Coordinates (0, 0) given for CNSZX are a placeholder and were ignored; its position in MovementRequest.getCoordinates() was used.");
    }

    @Test
    void coordinatesNearTheOverrideCarryNoWarningEvenFarFromTheListedPosition() {
        MovementRequest request = pickup(Location.fromCode("CNSZX", ALPS)).putCoordinate("CNSZX", new Coordinate(10.01, 46.51));

        assertThat(firstLeg(request).feature().properties().getLocationWarning()).isNull();
    }

    @Test
    void coordinatesFarFromTheOverrideAreComparedWithTheOverride() {
        MovementRequest request = pickup(Location.fromCode("CNSZX", ALPS)).putCoordinate("CNSZX", SHENZHEN_CITY);

        assertThat(firstLeg(request).feature().properties().getLocationWarning())
                .startsWith("Coordinates given for CNSZX lie ")
                .contains("km from its position in MovementRequest.getCoordinates()");
    }

    @Test
    void sharedStopCarriesTheWarningOnBothLegs() {
        Location dock = Location.fromCode("CNSHG", ALPS);
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg(Location.fromCode("CNSZX"), dock, TransportMode.ROAD, LegKind.PICKUP),
                new MovementLeg(dock, Location.fromCode("CNSZX"), TransportMode.ROAD, LegKind.DELIVERY)));

        List<LegResult> legs = DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs();

        assertThat(legs.get(0).feature().properties().getLocationWarning()).startsWith("Coordinates given for CNSHG lie ");
        assertThat(legs.get(1).feature().properties().getLocationWarning())
                .isEqualTo(legs.get(0).feature().properties().getLocationWarning());
    }

    @Test
    void samePlaceRoadLegNeedsNoConfiguredRoadSpeed() {
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg("CNSHG", "CNSHG", TransportMode.ROAD, LegKind.PICKUP)))
                .setRoadDurationMode(RoadDurationMode.ROUTE_DURATION_WHEN_AVAILABLE);
        request.getSpeedsKmh().remove(TransportMode.ROAD);

        LegResult leg = firstLeg(request);

        assertThat(leg.length()).isEqualTo(0.0);
        assertThat(leg.durationHours()).isEqualTo(0.0);
    }

    @Test
    void samePlaceholderAtBothEndsIsReportedOnce() {
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg(
                        Location.fromCode("CNSHG", PLACEHOLDER),
                        Location.fromCode("CNSHG", PLACEHOLDER),
                        TransportMode.ROAD,
                        LegKind.PICKUP),
                new MovementLeg("CNSHG", "SGSIN", TransportMode.SEA)));

        assertThat(firstLeg(request).feature().properties().getLocationWarning())
                .isEqualTo("Coordinates (0, 0) given for CNSHG are a placeholder and were ignored; the code's own position was used.");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, Double.NaN, Double.NEGATIVE_INFINITY})
    void invalidToleranceIsRejected(double tolerance) {
        MovementRequest request = pickup(Location.fromCode("CNSZX")).setCoordinateCodeToleranceKm(tolerance);

        assertThatThrownBy(() -> firstLeg(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Coordinate and code tolerance must be non-negative");
    }

    @Test
    void samePlaceRoadLegIsExactlyZeroWithoutAnEstimateWarning() {
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg("CNSHG", "CNSHG", TransportMode.ROAD, LegKind.PICKUP),
                new MovementLeg("CNSHG", "SGSIN", TransportMode.SEA)))
                // No provider is configured, so a leg that needed routing would throw.
                .setRoadRoutingMode(RoadRoutingMode.REQUIRE_NETWORK);

        LegResult leg = firstLeg(request);

        assertThat(leg.length()).isEqualTo(0.0);
        assertThat(leg.durationHours()).isEqualTo(0.0);
        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("great_circle");
        assertThat(leg.feature().properties().getDistanceWarning()).isNull();
        assertThat(leg.feature().properties().getLocationWarning()).isNull();
    }

    @Test
    void suppliedRoadRouteStillWinsForASamePlaceLeg() {
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg("CNSHG", "CNSHG", TransportMode.ROAD, LegKind.PICKUP)));
        request.getRoadRouteOverrides().put(1, new SuppliedRoute(12.0, "yard"));

        LegResult leg = firstLeg(request);

        assertThat(leg.length()).isEqualTo(12.0);
        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("supplied");
    }

    @Test
    void samePlaceRailLegIsExactlyZeroWithoutAWarning() {
        MovementRequest request = new MovementRequest(List.of(new MovementLeg("DEDUI", "DEDUI", TransportMode.RAIL)));

        LegResult leg = firstLeg(request);

        assertThat(leg.length()).isEqualTo(0.0);
        assertThat(leg.feature().properties().getDistanceWarning()).isNull();
    }
}
