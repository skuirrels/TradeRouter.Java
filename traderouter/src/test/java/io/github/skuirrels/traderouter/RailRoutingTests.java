package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.geojson.TradeRouterProperties;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.movements.EmissionFactors;
import io.github.skuirrels.traderouter.movements.LegKind;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.Location;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.RailRoutingMode;
import io.github.skuirrels.traderouter.movements.SuppliedRoute;
import io.github.skuirrels.traderouter.movements.TransportMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RailRoutingTests {
    private static final Coordinate LOS_ANGELES = new Coordinate(-118.2437, 34.0522);
    private static final Coordinate CHICAGO = new Coordinate(-87.6298, 41.8781);
    private static final Coordinate LONDON = new Coordinate(-0.1278, 51.5074);
    private static final Coordinate FELIXSTOWE = new Coordinate(1.3108, 51.9630);

    private static MovementRequest railLeg(Coordinate from, String fromName, Coordinate to, String toName) {
        return new MovementRequest(List.of(new MovementLeg(
                Location.fromCoordinate(from, fromName),
                Location.fromCoordinate(to, toName),
                TransportMode.RAIL,
                LegKind.MAIN)));
    }

    private static LegResult singleLeg(MovementRequest request) {
        List<LegResult> legs = DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs();
        assertThat(legs).hasSize(1);
        return legs.get(0);
    }

    @Test
    void railLegInNorthAmericaIsRoutedOnTheNarnMainLine() {
        LegResult leg = singleLeg(railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago"));
        TradeRouterProperties properties = leg.feature().properties();

        assertThat(properties.getDistanceBasis()).isEqualTo("rail_network");
        assertThat(properties.getDistanceSource()).isEqualTo("narn");
        assertThat(properties.getGeometryBasis()).isEqualTo("rail_network");
        assertThat(properties.getDistanceWarning()).isNull();
        // Los Angeles to Chicago is about 2,800 km in a straight line and about 3,500 km by BNSF's Southern Transcon.
        assertThat(properties.getStraightLineLength()).isBetween(2_780.0, 2_820.0);
        assertThat(leg.length()).isBetween(3_300.0, 3_800.0);
        assertThat(properties.getRailroads()).isNotEmpty();
        assertThat(properties.getRailroads()).anyMatch(mark -> mark.equals("BNSF") || mark.equals("UP"));
        List<double[]> positions = leg.feature().geometry().positions();
        assertThat(positions.size()).isGreaterThan(100);
        assertThat(positions.get(0)[0]).isCloseTo(LOS_ANGELES.longitude(), within(1e-9));
        assertThat(positions.get(positions.size() - 1)[1]).isCloseTo(CHICAGO.latitude(), within(1e-9));
        assertThat(leg.durationHours()).isCloseTo(leg.length() / 80.0, within(1e-6));
    }

    @Test
    void railLegOutsideNorthAmericaKeepsGreatCircleDistanceWithAWarning() {
        LegResult leg = singleLeg(railLeg(LONDON, "London", FELIXSTOWE, "Felixstowe"));

        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("great_circle");
        assertThat(leg.feature().properties().getRailroads()).isNull();
        // The warning names the end further from the network.
        assertThat(leg.feature().properties().getDistanceWarning())
                .contains("Felixstowe")
                .contains("North American rail network");
        assertThat(leg.length()).isBetween(100.0, 130.0);
    }

    @Test
    void railEndBeyondTheSnapDistanceKeepsGreatCircleDistance() {
        MovementRequest request = railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago");
        request.setRailNetworkSnapKm(0.0);

        LegResult leg = singleLeg(request);

        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("great_circle");
        assertThat(leg.feature().properties().getDistanceWarning()).contains("beyond the 0 km limit");
    }

    @Test
    void railGreatCircleOnlyDoesNotUseTheNetwork() {
        MovementRequest request = railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago");
        request.setRailRoutingMode(RailRoutingMode.GREAT_CIRCLE_ONLY);

        LegResult leg = singleLeg(request);

        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("great_circle");
        assertThat(leg.feature().properties().getDistanceWarning()).isNull();
        assertThat(leg.length()).isBetween(2_780.0, 2_820.0);
    }

    @Test
    void railNegativeSnapDistanceIsRejected() {
        MovementRequest request = railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago");
        request.setRailNetworkSnapKm(-1.0);

        assertThatThrownBy(() -> DefaultTradeRouterEngine.getDefault().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Rail network snap distance must be finite and non-negative.");
    }

    @Test
    void railSuppliedRouteOverridesGreatCircleAndDrivesEmissions() {
        MovementRequest request = new MovementRequest(MovementParser.parse("DEHAM to CZPRG Rail"))
                .setCargoTonnes(20.0);
        request.putRailRouteOverride(1, new SuppliedRoute(640.0, "rail-planner"));

        LegResult rail = singleLeg(request);

        assertThat(rail.length()).isEqualTo(640.0);
        assertThat(rail.durationHours()).isCloseTo(640.0 / 80.0, within(1e-9));
        assertThat(rail.co2eKgPerTonne()).isCloseTo(19.648, within(1e-9));
        assertThat(rail.co2eKg()).isCloseTo(392.96, within(1e-9));
        TradeRouterProperties properties = rail.feature().properties();
        assertThat(properties.getDistanceBasis()).isEqualTo("supplied");
        assertThat(properties.getDistanceSource()).isEqualTo("rail-planner");
        assertThat(properties.getDistanceWarning()).isNull();
        assertThat(properties.getDurationBasis()).isEqualTo("assumed_speed");
        assertThat(properties.getGeometryBasis()).isEqualTo("great_circle");
        assertThat(properties.getStraightLineLength()).isBetween(480.0, 500.0);
    }

    @Test
    void railSuppliedDurationAndGeometryAreUsedAsGiven() {
        Coordinate berlin = new Coordinate(13.4, 52.5);
        MovementRequest request = new MovementRequest(MovementParser.parse("DEHAM to CZPRG Rail"));
        request.putRailRouteOverride(1, new SuppliedRoute(
                660.0, 14.0, List.of(new Coordinate(10.0, 53.55), berlin), "rail-planner"));

        LegResult rail = singleLeg(request);

        assertThat(rail.durationHours()).isEqualTo(14.0);
        assertThat(rail.feature().properties().getDurationBasis()).isEqualTo("supplied");
        assertThat(rail.feature().properties().getGeometryBasis()).isEqualTo("supplied");
        List<double[]> positions = rail.feature().geometry().positions();
        assertThat(positions).hasSize(4);
        assertThat(positions.get(0)[0]).isCloseTo(rail.from().coordinate().longitude(), within(1e-9));
        assertThat(positions.get(2)[0]).isCloseTo(berlin.longitude(), within(1e-9));
        assertThat(positions.get(2)[1]).isCloseTo(berlin.latitude(), within(1e-9));
        assertThat(positions.get(positions.size() - 1)[1]).isCloseTo(rail.to().coordinate().latitude(), within(1e-9));
    }

    @Test
    void railSuppliedRouteTakesPrecedenceOverTheNetwork() {
        MovementRequest request = railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago");
        request.putRailRouteOverride(1, new SuppliedRoute(3_600.0, "rail-planner"));

        LegResult leg = singleLeg(request);

        assertThat(leg.length()).isEqualTo(3_600.0);
        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("supplied");
        assertThat(leg.feature().properties().getRailroads()).isNull();
    }

    @Test
    void railOverrideAppliesOnlyToItsSequence() {
        MovementRequest request = new MovementRequest(MovementParser.parse("DEHAM to CZPRG Rail\nCZPRG to DEDUI Rail"));
        request.putRailRouteOverride(2, new SuppliedRoute(700.0, "rail-planner"));

        List<LegResult> legs = DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs();

        assertThat(legs.get(0).feature().properties().getDistanceBasis()).isEqualTo("great_circle");
        assertThat(legs.get(1).feature().properties().getDistanceBasis()).isEqualTo("supplied");
        assertThat(legs.get(1).length()).isEqualTo(700.0);
    }

    @Test
    void railNorthAmericanNetworkLegUsesTheUsAverageFactor() {
        MovementRequest request = railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago");
        request.setCargoTonnes(12.0);

        LegResult leg = singleLeg(request);

        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("rail_network");
        assertThat(leg.co2eGramsPerTonneKm()).isEqualTo(16.1);
        assertThat(leg.co2eKgPerTonne()).isCloseTo(16.1 * leg.length() / 1000.0, within(1e-9));
        assertThat(leg.co2eKg()).isCloseTo(16.1 * leg.length() / 1000.0 * 12.0, within(1e-9));
    }

    @Test
    void railNorthAmericanFactorAppliesToSuppliedAndGreatCircleLegs() {
        MovementRequest supplied = railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago");
        supplied.putRailRouteOverride(1, new SuppliedRoute(3_600.0, "rail-planner"));
        MovementRequest greatCircle = railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago");
        greatCircle.setRailRoutingMode(RailRoutingMode.GREAT_CIRCLE_ONLY);

        assertThat(singleLeg(supplied).co2eGramsPerTonneKm()).isEqualTo(16.1);
        assertThat(singleLeg(greatCircle).co2eGramsPerTonneKm()).isEqualTo(16.1);
    }

    @Test
    void railLegOutsideNorthAmericaKeepsTheEuropeanDieselFactor() {
        LegResult leg = singleLeg(railLeg(LONDON, "London", FELIXSTOWE, "Felixstowe"));

        assertThat(leg.co2eGramsPerTonneKm()).isEqualTo(30.7);
    }

    @Test
    void railNorthAmericanFactorCanBeOverridden() {
        MovementRequest request = railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago");
        request.setEmissions(EmissionFactors.glecDefaults().setRailNorthAmericaGramsPerTonneKm(20.0));

        assertThat(singleLeg(request).co2eGramsPerTonneKm()).isEqualTo(20.0);
    }

    @Test
    void railNegativeNorthAmericanFactorIsRejected() {
        MovementRequest request = railLeg(LOS_ANGELES, "Los Angeles", CHICAGO, "Chicago");
        request.setEmissions(EmissionFactors.glecDefaults().setRailNorthAmericaGramsPerTonneKm(-1.0));

        assertThatThrownBy(() -> DefaultTradeRouterEngine.getDefault().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("railNorthAmericaGramsPerTonneKm");
    }

    @Test
    void railGraphHoldsTheConnectedMainLine() {
        MaritimeGraph graph = DefaultTradeRouterEngine.getDefault().railGraph();

        assertThat(graph.isReadOnly()).isTrue();
        assertThat(graph.nodeCount()).isGreaterThan(10_000);
        int losAngeles = graph.findNearestNode(LOS_ANGELES);
        assertThat(Haversine.distanceKm(LOS_ANGELES, graph.getCoordinate(losAngeles))).isLessThan(10.0);
    }
}
