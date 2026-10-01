package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.movements.LegKind;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.Location;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.RoadDistanceEstimate;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRouteStatus;
import io.github.skuirrels.traderouter.movements.RoadRoutingMode;
import io.github.skuirrels.traderouter.movements.SuppliedRoute;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Port of TradeRouter.Net MovementValidationTests. Expected messages use the .NET wildcard form, where {@code *}
 * matches any text; {@link #glob(String)} turns them into a regular expression.
 *
 * <p>Not ported, because Java cannot express the invalid input: the C# cases that cast 99 to an enum ("unsupported
 * speed mode", "unknown leg mode", "unknown leg kind", "unknown waypoint kind") and the unknown provider status
 * test. {@code MovementLeg} and {@code RoadRouteResult} reject null enum values in their constructors, and
 * {@code getSpeedsKmh()} is an {@code EnumMap} that rejects a null key.
 */
class MovementValidationTests {
    private static final Coordinate INLAND = new Coordinate(1.0, 52.0);
    private static final Coordinate QUAY = new Coordinate(1.35, 51.96);
    private static final Coordinate FAR_QUAY = new Coordinate(4.5, 51.9);
    private static final Coordinate WAREHOUSE = new Coordinate(4.8, 52.1);

    private static DefaultTradeRouterEngine engine() {
        return DefaultTradeRouterEngine.getDefault();
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                invalid("no legs", r -> r.getLegs().clear(),
                        "A movement needs at least one leg.*"),
                invalid("null emissions", r -> r.setEmissions(null),
                        "Movement emissions cannot be null.*"),
                invalid("null estimator", r -> r.setRoadDistanceEstimator(null),
                        "Movement road-distance estimator cannot be null.*"),
                invalid("unknown routing mode", r -> r.setRoadRoutingMode(null),
                        "Movement road-routing mode is unknown.*"),
                invalid("negative dwell", r -> r.setPortDwellHours(-1.0),
                        "Port dwell must be finite and non-negative.*"),
                invalid("NaN dwell", r -> r.setPortDwellHours(Double.NaN),
                        "Port dwell must be finite and non-negative.*"),
                invalid("zero road speed", r -> r.getSpeedsKmh().put(TransportMode.ROAD, 0.0),
                        "Speed for Road must be finite and positive.*"),
                invalid("infinite air speed", r -> r.getSpeedsKmh().put(TransportMode.AIR, Double.POSITIVE_INFINITY),
                        "Speed for Air must be finite and positive.*"),
                invalid("pickup not first", r -> r.getLegs().set(1, new MovementLeg(
                                Location.fromCoordinate(QUAY), Location.fromCoordinate(FAR_QUAY), TransportMode.SEA, LegKind.PICKUP)),
                        "Pickup leg 2 must be the first leg.*"),
                invalid("delivery not last", r -> r.getLegs().set(1, new MovementLeg(
                                Location.fromCoordinate(QUAY), Location.fromCoordinate(FAR_QUAY), TransportMode.SEA, LegKind.DELIVERY)),
                        "Delivery leg 2 must be the last leg.*"),
                invalid("blank coordinate key", r -> r.getCoordinates().put(" ", INLAND),
                        "Movement coordinate keys cannot be blank.*"),
                invalid("invalid coordinate value", r -> r.getCoordinates().put("XXAAA", new Coordinate(0, 95)),
                        "Latitude must be between -90 and 90 degrees.*"),
                invalid("override sequence zero", r -> r.getRoadRouteOverrides().put(0, route(10)),
                        "Road route override sequence 0 is outside the movement's leg range.*"),
                invalid("override sequence past end", r -> r.getRoadRouteOverrides().put(4, route(10)),
                        "Road route override sequence 4 is outside the movement's leg range.*"),
                invalid("override on sea leg", r -> r.getRoadRouteOverrides().put(2, route(10)),
                        "Road route override sequence 2 does not refer to a road leg.*"),
                invalid("null override", r -> r.getRoadRouteOverrides().put(1, null),
                        "Road route override sequence 1 is null.*"),
                invalid("NaN override distance", r -> r.getRoadRouteOverrides().put(1, route(Double.NaN)),
                        "Road route override sequence 1 has an invalid distance.*"),
                invalid("negative override distance", r -> r.getRoadRouteOverrides().put(1, route(-1)),
                        "Road route override sequence 1 has an invalid distance.*"),
                invalid("negative override duration",
                        r -> r.getRoadRouteOverrides().put(1, new SuppliedRoute(10, -1.0, null, null)),
                        "Road route override sequence 1 has an invalid duration.*"),
                invalid("zero override duration",
                        r -> r.getRoadRouteOverrides().put(1, new SuppliedRoute(10, 0.0, null, null)),
                        "Road route override sequence 1 has a non-positive duration for a non-zero distance.*"),
                invalid("blank override source",
                        r -> r.getRoadRouteOverrides().put(1, new SuppliedRoute(10, null, null, " ")),
                        "Road route override sequence 1 has a blank source.*"),
                invalid("single-point override geometry",
                        r -> r.getRoadRouteOverrides().put(1, new SuppliedRoute(10, null, List.of(INLAND), null)),
                        "Road route override sequence 1 geometry needs at least two positions.*"),
                invalid("invalid override geometry",
                        r -> r.getRoadRouteOverrides().put(1, new SuppliedRoute(10, null, List.of(INLAND, new Coordinate(1, 95)), null)),
                        "Latitude must be between -90 and 90 degrees.*"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void calculateMovementRejectsInvalidRequest(Consumer<MovementRequest> invalidRequest, String expectedMessage) {
        MovementRequest request = createValidRequest();
        invalidRequest.accept(request);

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching(glob(expectedMessage));
    }

    @Test
    void calculateMovementValidBaseRequestSucceeds() {
        var result = engine().calculateMovement(createValidRequest());

        assertThat(result.legs()).hasSize(3);
    }

    @Test
    void calculateMovementAcceptsZeroDistanceOverrideWithZeroDuration() {
        MovementRequest request = createValidRequest();
        request.getRoadRouteOverrides().put(1, new SuppliedRoute(0.0, 0.0, null, null));

        LegResult road = engine().calculateMovement(request).legs().get(0);

        assertThat(road.length()).isEqualTo(0.0);
        assertThat(road.durationHours()).isEqualTo(0.0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "UNAVAILABLE      | provider detail | Road leg 1 (GBLGW to GBLHR) could not be routed by stub: provider detail",
            "OUTSIDE_COVERAGE | null            | Road leg 1 (GBLGW to GBLHR) could not be routed by stub: OutsideCoverage"
    })
    void calculateMovementAsyncRequireNetworkRejectsProviderFailure(RoadRouteStatus status, String message, String expected) {
        MovementRequest request = createProviderRequest(
                RoadRouteResult.builder(status, "stub").message(message).build(), RoadRoutingMode.REQUIRE_NETWORK);

        assertThatThrownBy(() -> engine().calculateMovementAsync(request).join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expected);
    }

    @Test
    void calculateMovementAsyncRejectsNullProviderResult() {
        MovementRequest request = createProviderRequest(null, RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE);

        assertThatThrownBy(() -> engine().calculateMovementAsync(request).join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Road-route provider returned null for leg 1.");
    }

    static Stream<Arguments> invalidProviderSuccesses() {
        return Stream.of(
                Arguments.of(success().build(), "*invalid distance for leg 1."),
                Arguments.of(success().distanceKm(Double.NaN).build(), "*invalid distance for leg 1."),
                Arguments.of(success().distanceKm(-1.0).build(), "*invalid distance for leg 1."),
                Arguments.of(success().distanceKm(50.0).durationHours(Double.NaN).build(), "*invalid duration for leg 1."),
                Arguments.of(success().distanceKm(50.0).durationHours(-1.0).build(), "*invalid duration for leg 1."),
                Arguments.of(success().distanceKm(50.0).durationHours(1.0).geometry(List.of(new Coordinate(-0.19, 51.15))).build(),
                        "*fewer than two geometry positions for leg 1."));
    }

    @ParameterizedTest
    @MethodSource("invalidProviderSuccesses")
    void calculateMovementAsyncRejectsInvalidProviderSuccess(RoadRouteResult result, String expected) {
        MovementRequest request = createProviderRequest(result, RoadRoutingMode.REQUIRE_NETWORK);

        assertThatThrownBy(() -> engine().calculateMovementAsync(request).join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageMatching(glob(expected));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "null                | Road-distance estimator returned null for leg 1.",
            "blank model         | Road-distance estimator returned a blank model for leg 1.",
            "blank warning       | Road-distance estimator returned a blank warning for leg 1.",
            "NaN distance        | Road-distance estimator 'stub' returned NaN km for a * km great-circle lower bound.",
            "below straight line | Road-distance estimator 'stub' returned 1 km for a * km great-circle lower bound."
    })
    void calculateMovementRejectsInvalidEstimatorOutput(String estimate, String expected) {
        RoadDistanceEstimate output = switch (estimate) {
            case "null" -> null;
            case "blank model" -> new RoadDistanceEstimate(100, " ", "warning");
            case "blank warning" -> new RoadDistanceEstimate(100, "stub", "");
            case "NaN distance" -> new RoadDistanceEstimate(Double.NaN, "stub", "warning");
            default -> new RoadDistanceEstimate(1, "stub", "warning");
        };
        MovementRequest request = MovementRequest.of("Pickup GBLGW to Airport GBLHR Road")
                .setRoadDistanceEstimator(r -> output);

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageMatching(glob(expected));
    }

    @Test
    void calculateMovementAsyncProviderFallbackWarningPrefixesEstimatorWarning() {
        MovementRequest request = createProviderRequest(
                RoadRouteResult.builder(RoadRouteStatus.UNAVAILABLE, "stub").build(),
                RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE);
        request.setRoadDistanceEstimator(r -> new RoadDistanceEstimate(100, "stub-model", "Stub warning."));

        List<LegResult> legs = engine().calculateMovementAsync(request).join().legs();

        assertThat(legs).hasSize(1);
        LegResult road = legs.get(0);
        assertThat(road.feature().properties().getDistanceWarning())
                .isEqualTo("stub returned Unavailable: no reason supplied. Stub warning.");
        assertThat(road.feature().properties().getDistanceSource()).isEqualTo("stub-model");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "GBLHR to GBFXT Sea  | Leg 1 uses Sea, but its from location GBLHR is recorded as airport rather than a compatible sea terminal. If the UN/LOCODE record is incomplete*",
            "GBFXT to GBLHR Air  | Leg 1 uses Air, but its from location GBFXT is recorded as sea port rather than a compatible air terminal. If the UN/LOCODE record is incomplete*",
            "GBFXT to DEDUI Rail | Leg 1 uses Rail, but its from location GBFXT is recorded as sea port rather than a compatible rail terminal. If the UN/LOCODE record is incomplete*",
            "DEDUI to GBLHR Rail | Leg 1 uses Rail, but its to location GBLHR is recorded as airport rather than a compatible rail terminal. If the UN/LOCODE record is incomplete*"
    })
    void calculateMovementRejectsLocationWithoutFunctionForMode(String legs, String expected) {
        assertThatThrownBy(() -> TradeRoutes.calculateMovement(legs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching(glob(expected));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "Station GBFXT to Station DEDUI Rail  | *GBFXT is declared as Station, but UN/LOCODE records sea port. If the UN/LOCODE record is incomplete*",
            "Terminal GBLHR to Terminal DEDUI Road | *GBLHR is declared as Terminal, but UN/LOCODE records airport. If the UN/LOCODE record is incomplete*",
            "Depot GBLGW to Depot DEDUI Road       | *GBLGW is declared as Depot, but UN/LOCODE records airport. If the UN/LOCODE record is incomplete*",
            "Depot DEDUI to Depot GBFXT Road       | Leg 1 to location GBFXT is declared as Depot, but UN/LOCODE records sea port. If the UN/LOCODE record is incomplete*"
    })
    void calculateMovementRejectsWaypointKindTheLocationDoesNotServe(String legs, String expected) {
        assertThatThrownBy(() -> TradeRoutes.calculateMovement(legs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching(glob(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Station DEHAM to Station DEDUI Rail",
            "Terminal GBFXT to Terminal DEDUI Road",
            "Depot DEHAM to Depot DEDUI Road",
            "Terminal NLRTM to Depot DEDUI Rail"
    })
    void calculateMovementAcceptsWaypointKindTheLocationServes(String legs) {
        var result = TradeRoutes.calculateMovement(legs);

        assertThat(result.legs()).singleElement()
                .satisfies(leg -> assertThat(leg.length()).isGreaterThan(0.0));
    }

    @Test
    void calculateMovementTypedPlanWaypointsAreValidatedLikeParsedKinds() {
        MovementPlan accepted = MovementPlan
                .from(Waypoint.station("DEHAM"))
                .thenTo(Waypoint.depot("DEDUI"), TransportMode.RAIL)
                .thenTo(Waypoint.terminal("NLRTM"), TransportMode.ROAD);
        MovementPlan rejected = MovementPlan
                .from(Waypoint.station("GBFXT"))
                .thenTo(Waypoint.station("DEDUI"), TransportMode.RAIL);

        assertThat(TradeRoutes.calculateMovement(accepted).legs()).hasSize(2);
        assertThatThrownBy(() -> TradeRoutes.calculateMovement(rejected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageMatching(glob("*GBFXT is declared as Station*"));
    }

    @Test
    void calculateMovementCoordinateOnlyLocationsHaveNoFunctionToCheck() {
        MovementRequest request = new MovementRequest(List.of(new MovementLeg(
                Location.fromCoordinate(INLAND), Location.fromCoordinate(WAREHOUSE), TransportMode.RAIL)));

        assertThat(engine().calculateMovement(request).legs()).hasSize(1);
    }

    private static MovementRequest createValidRequest() {
        List<MovementLeg> legs = new ArrayList<>();
        legs.add(new MovementLeg(Location.fromCoordinate(INLAND), Location.fromCoordinate(QUAY), TransportMode.ROAD, LegKind.PICKUP));
        legs.add(new MovementLeg(Location.fromCoordinate(QUAY), Location.fromCoordinate(FAR_QUAY), TransportMode.SEA));
        legs.add(new MovementLeg(Location.fromCoordinate(FAR_QUAY), Location.fromCoordinate(WAREHOUSE), TransportMode.ROAD, LegKind.DELIVERY));
        return new MovementRequest(legs);
    }

    private static MovementRequest createProviderRequest(RoadRouteResult result, RoadRoutingMode mode) {
        return MovementRequest.of("Pickup GBLGW to Airport GBLHR Road")
                .setRoadRouteProvider(r -> CompletableFuture.completedFuture(result))
                .setRoadRoutingMode(mode);
    }

    private static Arguments invalid(String name, Consumer<MovementRequest> mutation, String expectedMessage) {
        return Arguments.of(Named.of(name, mutation), expectedMessage);
    }

    private static SuppliedRoute route(double distanceKm) {
        return new SuppliedRoute(distanceKm, null, null, null);
    }

    private static RoadRouteResult.Builder success() {
        return RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "stub");
    }

    /** Converts a .NET-style wildcard message, where {@code *} matches any text, to a regular expression. */
    private static String glob(String pattern) {
        StringBuilder regex = new StringBuilder("(?s)");
        int start = 0;
        int star;
        while ((star = pattern.indexOf('*', start)) >= 0) {
            if (star > start) {
                regex.append(Pattern.quote(pattern.substring(start, star)));
            }
            regex.append(".*");
            start = star + 1;
        }
        if (start < pattern.length()) {
            regex.append(Pattern.quote(pattern.substring(start)));
        }
        return regex.toString();
    }
}
