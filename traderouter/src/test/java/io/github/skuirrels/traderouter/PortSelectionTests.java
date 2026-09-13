package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.ports.AreaFeature;
import io.github.skuirrels.traderouter.ports.Port;
import io.github.skuirrels.traderouter.ports.PortDatabase;
import io.github.skuirrels.traderouter.ports.PortParameters;
import io.github.skuirrels.traderouter.ports.PortProps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortSelectionTests {
    private static final List<Coordinate> BELGIUM_POLYGON = List.of(
            new Coordinate(2.5390082105781664, 51.12935353101912),
            new Coordinate(2.658422, 50.796848),
            new Coordinate(3.123252, 50.780363),
            new Coordinate(4.1797570873041145, 50.02875404340671),
            new Coordinate(4.88490130124042, 50.15316867323778),
            new Coordinate(4.84369916664572, 49.816903537989774),
            new Coordinate(5.367505366280012, 49.660158663860244),
            new Coordinate(5.462583288120824, 49.501950558185754),
            new Coordinate(5.838619986971207, 49.60595170762344),
            new Coordinate(5.738394125385502, 49.96328692553026),
            new Coordinate(6.412540492173664, 50.38040683350353),
            new Coordinate(5.740262760313543, 50.81332955689459),
            new Coordinate(5.823088307078621, 51.12406851908984),
            new Coordinate(4.705997, 51.474099),
            new Coordinate(3.830289, 51.620545),
            new Coordinate(3.314971, 51.345781),
            new Coordinate(2.5390082105781664, 51.12935353101912));

    @Test
    void portDatabaseLoadEmbeddedShouldContainPorts() {
        PortDatabase db = DefaultTradeRouterEngine.getDefault().ports();
        assertThat(db.count()).isGreaterThan(3000);

        Port leHavre = db.getByCode("FRLEH");
        assertThat(leHavre).isNotNull();
        assertThat(leHavre.name()).isEqualTo("Le Havre");

        Port singapore = db.getByCode("SGSIN");
        assertThat(singapore).isNotNull();
        assertThat(singapore.name()).isEqualTo("Singapore");
    }

    @Test
    void queryClosestPortWithTerminalFilterShouldRespectTerminalFlag() {
        PortDatabase db = DefaultTradeRouterEngine.getDefault().ports();
        Coordinate paris = new Coordinate(2.333333, 48.866667);

        Port anyPort = db.queryClosestPort(paris);
        assertThat(anyPort).isNotNull();

        Port terminalPort = db.queryClosestPort(paris, true, null, null, true);
        assertThat(terminalPort).isNotNull();
        assertThat(terminalPort.isTerminal()).isTrue();
    }

    @Test
    void areaFeatureContainsBrusselsPointShouldBeTrue() {
        Coordinate brussels = new Coordinate(4.352066635732303, 50.85097556499499);
        AreaFeature areaBE = new AreaFeature(BELGIUM_POLYGON, "BE");

        assertThat(areaBE.contains(brussels)).isTrue();

        // Paris is outside Belgium
        Coordinate paris = new Coordinate(2.333333, 48.866667);
        assertThat(areaBE.contains(paris)).isFalse();
    }

    @Test
    void areaFeatureMultiRouteGenerationReturnsRoutesForBothPreferredPorts() {
        PortProps firstPort = new PortProps("FRLEH", 200);
        PortProps secondPort = new PortProps("BEANR", 250);

        AreaFeature areaBE = new AreaFeature(BELGIUM_POLYGON, "BE", List.of(firstPort, secondPort));

        PortParameters portParam = new PortParameters().setPortsInAreasFrom(List.of(areaBE));

        Coordinate brussels = new Coordinate(4.352066635732303, 50.85097556499499);
        Coordinate tokyo = new Coordinate(139.67917395748216, 35.77846652689662);

        TradeRouterOptions options = new TradeRouterOptions()
                .setAppendOriginDestination(false)
                .setIncludePorts(true)
                .setPortParameters(portParam);

        List<GeoJsonFeature> routes = TradeRoutes.calculateRoutes(brussels, tokyo, options);
        assertThat(routes).hasSize(2);

        List<String> originPortCodes = routes.stream().map(r -> r.properties().getPortOrigin().portCode()).toList();
        assertThat(originPortCodes).containsExactlyInAnyOrder("BEANR", "FRLEH");
        assertThat(routes.get(0).properties().getPortOrigin().share()).isCloseTo(250.0 / 450.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void calculateRouteByPortCodesPopulatesPortMetadata() {
        GeoJsonFeature route = TradeRoutes.calculate("FRLEH", "SGSIN");

        assertThat(route).isNotNull();
        assertThat(route.properties().getPortOrigin()).isNotNull();
        assertThat(route.properties().getPortOrigin().portCode()).isEqualTo("FRLEH");
        assertThat(route.properties().getPortOrigin().name()).isEqualTo("Le Havre");

        assertThat(route.properties().getPortDest()).isNotNull();
        assertThat(route.properties().getPortDest().portCode()).isEqualTo("SGSIN");
        assertThat(route.properties().getPortDest().name()).isEqualTo("Singapore");

        assertThat(route.properties().getLength()).isGreaterThan(10000.0);
    }

    @Test
    void inlandPointsResolveToNearestTerminals() {
        GeoJsonFeature route = TradeRoutes.calculate(
                TradeRoutes.locate("FRPAR").coordinate(),
                TradeRoutes.locate("JPTYO").coordinate(),
                new TradeRouterOptions()
                        .setIncludePorts(true)
                        .setAppendOriginDestination(true)
                        .setPortParameters(new PortParameters().setOnlyTerminals(true)));

        assertThat(route.properties().getPortOrigin().portCode()).isEqualTo("FRURO");
        assertThat(route.properties().getPortDest().portCode()).isEqualTo("JPTYO");
    }
}
