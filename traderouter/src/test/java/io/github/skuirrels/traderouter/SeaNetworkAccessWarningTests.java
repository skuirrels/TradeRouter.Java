package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeaNetworkAccessWarningTests {
    private static final Coordinate MANAUS = new Coordinate(-60.0086, -3.144);
    private static final Coordinate ROTTERDAM = new Coordinate(4.0, 51.95);

    private static LegResult singleLeg(String legsText) {
        var legs = TradeRoutes.calculateMovement(legsText).legs();
        assertThat(legs).hasSize(1);
        return legs.get(0);
    }

    @Test
    void movementWarnsWhenAPortLiesFarFromTheMaritimeNetwork() {
        // Manaus, 1,500 km up the Amazon, which the network does not follow.
        LegResult leg = singleLeg("Port NLRTM to Port BRMAO Sea");

        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("maritime_network");
        assertThat(leg.feature().properties().getDistanceWarning())
                .matches("BRMAO is [0-9,]+ km from the nearest node of the maritime network, beyond the 50 km "
                        + "networkAccessWarningKm\\. The route joins the network there in a straight line, counted in "
                        + "the length.*")
                .doesNotContain("NLRTM");
    }

    @Test
    void movementNamesBothEndsWhenBothLieFarFromTheNetwork() {
        LegResult leg = singleLeg("Port BRMAO to Port CUMOA Sea");

        assertThat(leg.feature().properties().getDistanceWarning()).startsWith("BRMAO is ").contains(" and CUMOA is ");
    }

    @Test
    void movementDoesNotWarnBetweenWellConnectedPorts() {
        LegResult leg = singleLeg("Port NLRTM to Port SGSIN Sea");

        assertThat(leg.feature().properties().getDistanceWarning()).isNull();
    }

    @Test
    void movementThresholdFollowsTheSeaOptions() {
        // Moa, Cuba, lies about 73 km from the nearest node.
        assertThat(singleLeg("Port NLRTM to Port CUMOA Sea").feature().properties().getDistanceWarning())
                .contains("CUMOA is 7");

        MovementRequest request = new MovementRequest(MovementParser.parse("Port NLRTM to Port CUMOA Sea"))
                .setSeaOptions(new TradeRouterOptions().setNetworkAccessWarningKm(100.0));
        assertThat(DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs().get(0)
                .feature().properties().getDistanceWarning()).isNull();
    }

    @Test
    void routeInfiniteThresholdTurnsTheWarningOff() {
        GeoJsonFeature feature = TradeRoutes.calculate(MANAUS, ROTTERDAM,
                new TradeRouterOptions().setNetworkAccessWarningKm(Double.POSITIVE_INFINITY));

        assertThat(feature.properties().getDistanceWarning()).isNull();
    }

    @Test
    void routeSaysTheLengthLeavesTheGapOutWithoutAppendedEnds() {
        GeoJsonFeature feature = TradeRoutes.calculate(MANAUS, ROTTERDAM);

        assertThat(feature.properties().getDistanceWarning())
                .startsWith("The origin is ")
                .endsWith("The route does not run to the requested point, so its length leaves part or all of the gap out.");
    }

    @Test
    void routeSaysTheGapIsCountedWhenEndsAreAppended() {
        GeoJsonFeature feature = TradeRoutes.calculate(MANAUS, ROTTERDAM,
                new TradeRouterOptions().setAppendOriginDestination(true));

        assertThat(feature.properties().getDistanceWarning())
                .startsWith("The origin is ")
                .endsWith("The route joins the network there in a straight line, counted in the length, which may cross land and is not a sailed track.");
    }

    @Test
    void routeCapitalisesTheDestinationWhenOnlyItLiesFar() {
        assertThat(TradeRoutes.calculate(ROTTERDAM, MANAUS).properties().getDistanceWarning())
                .startsWith("The destination is ");
    }

    @Test
    void routeMeasuresFromTheRequestedPointWhenPortsAreIncluded() {
        GeoJsonFeature feature = TradeRoutes.calculate(MANAUS, ROTTERDAM, new TradeRouterOptions().setIncludePorts(true));

        assertThat(feature.properties().getDistanceWarning()).startsWith("The origin is ").doesNotContain("the destination");
    }

    @Test
    void routeSaysAStraightLineWhenBothEndsSnapToOneNode() {
        // Two points a few kilometres apart in central Australia share the same nearest node, far inland.
        GeoJsonFeature feature = TradeRoutes.calculate(new Coordinate(133.88, -23.70), new Coordinate(133.92, -23.70));

        assertThat(feature.properties().getDistanceWarning())
                .startsWith("The origin is ")
                .contains(" and the destination is ")
                .endsWith("Both ends snap to the same node, so the route is a straight line between them, which may cross land and is not a sailed track.");
    }

    @Test
    void routeWarningReadsTheSameInEveryLocale() {
        String root = TradeRoutes.calculate(MANAUS, ROTTERDAM).properties().getDistanceWarning();

        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(TradeRoutes.calculate(MANAUS, ROTTERDAM).properties().getDistanceWarning())
                    .isEqualTo(root)
                    .contains(",");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void warningRoundsLikeDotNetInvariantN0() {
        // .NET's N0 rounds midpoints away from zero and groups thousands with commas.
        assertThat(DefaultTradeRouterEngine.describeNetworkAccess(null, 1_084.5, null, 2.5, 0.5, false, true))
                .isEqualTo("The origin is 1,085 km and the destination is 3 km from the nearest node of the maritime "
                        + "network, beyond the 1 km networkAccessWarningKm. The route joins the network there in a "
                        + "straight line, counted in the length, which may cross land and is not a sailed track.");
        assertThat(DefaultTradeRouterEngine.describeNetworkAccess("A", 50.0, "B", 50.0, 50.0, false, true)).isNull();
    }

    @Test
    void warningIsSerialised() {
        LegResult leg = singleLeg("Port NLRTM to Port BRMAO Sea");

        assertThat(leg.feature().toJson())
                .contains("\"distance_warning\"")
                .doesNotContain("NetworkAccessKm")
                .doesNotContain("network_access");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, Double.NaN})
    void optionsRejectANegativeOrUndefinedThreshold(double value) {
        assertThatThrownBy(() -> new TradeRouterOptions().setNetworkAccessWarningKm(value).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Network access warning distance must be non-negative.");
    }

    @Test
    void optionsCopyKeepsTheThreshold() {
        assertThat(new TradeRouterOptions().setNetworkAccessWarningKm(80.0).copy().getNetworkAccessWarningKm())
                .isEqualTo(80.0);
        assertThat(new TradeRouterOptions().getNetworkAccessWarningKm())
                .isEqualTo(TradeRouterOptions.DEFAULT_NETWORK_ACCESS_WARNING_KM)
                .isEqualTo(50.0);
    }
}
