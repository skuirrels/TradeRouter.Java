package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.ports.Port;
import io.github.skuirrels.traderouter.ports.PortDatabase;
import io.github.skuirrels.traderouter.ports.PortPair;
import io.github.skuirrels.traderouter.ports.PortParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PortFilteringTests {
    // Synthetic ports on the equator, so every expected choice follows from the fixture alone.
    private static final Coordinate NEAR_AA_ONE = new Coordinate(0.1, 0);
    private static final Coordinate NEAR_CC_ONE = new Coordinate(10.6, 0);

    private static PortDatabase createDatabase() {
        return new PortDatabase(List.of(
                createPort("AAONE", "Aland", 0.0, 0.0),
                createPort("AATWO", "Aland", 1.0, 1.0, "BB"),
                createPort("AATHR", "Aland", 2.0, 1.0, "cc"),
                createPort("BBONE", "Bbland", 10.0, 1.0, "AA"),
                createPort("CCONE", "Ccland", 10.5, 0.0)));
    }

    private static Port createPort(String code, String country, double longitude, double terminal, String... toCountries) {
        return Port.builder()
                .portCode(code)
                .name(code)
                .country(country)
                .terminalFlag(terminal)
                .toCountries(List.of(toCountries))
                .coordinate(new Coordinate(longitude, 0.0))
                .build();
    }

    @Test
    void queryClosestPortWithoutFiltersReturnsNearestPort() {
        assertThat(createDatabase().queryClosestPort(NEAR_AA_ONE).portCode()).isEqualTo("AAONE");
    }

    @Test
    void queryClosestPortOnlyTerminalsSkipsNearerNonTerminal() {
        assertThat(createDatabase().queryClosestPort(NEAR_AA_ONE, true, null, null, true).portCode()).isEqualTo("AATWO");
    }

    @ParameterizedTest
    @CsvSource({"BB, AATWO", "cc, AATHR", "C-C, AATHR"})
    void queryClosestPortToCountryKeepsOnlyPortsServingThatCountry(String toCountry, String expected) {
        assertThat(createDatabase().queryClosestPort(NEAR_AA_ONE, false, null, toCountry, true).portCode()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BB", "Bbland", "bb land"})
    void queryClosestPortCountryMatchesCodePrefixOrCountryName(String country) {
        assertThat(createDatabase().queryClosestPort(NEAR_AA_ONE, false, country, null, true).portCode()).isEqualTo("BBONE");
    }

    @Test
    void queryClosestPortStrictFilterMatchingNothingReturnsNull() {
        PortDatabase db = createDatabase();

        assertThat(db.queryClosestPort(NEAR_AA_ONE, false, "ZZ", null, true)).isNull();
        assertThat(db.queryClosestPort(NEAR_AA_ONE, false, null, "ZZ", true)).isNull();
    }

    @Test
    void queryClosestPortNonStrictFilterMatchingNothingFallsBackToNearestPort() {
        PortDatabase db = createDatabase();

        assertThat(db.queryClosestPort(NEAR_AA_ONE, false, "ZZ", null, false).portCode()).isEqualTo("AAONE");
        assertThat(db.queryClosestPort(NEAR_AA_ONE, false, null, "ZZ", false).portCode()).isEqualTo("AAONE");
    }

    @Test
    void queryClosestPortNonStrictDropsOnlyTheFilterThatMatchesNothing() {
        // CCONE is the only Ccland port and is not a terminal, so the country filter is dropped
        // while the terminal filter still applies.
        PortDatabase db = createDatabase();

        assertThat(db.queryClosestPort(NEAR_CC_ONE, true, "CC", null, true)).isNull();
        assertThat(db.queryClosestPort(NEAR_CC_ONE, true, "CC", null, false).portCode()).isEqualTo("BBONE");
    }

    @Test
    void queryClosestPortOnlyTerminalsWithNoTerminalsRespectsStrict() {
        PortDatabase db = new PortDatabase(List.of(createPort("AAONE", "Aland", 0.0, 0.0)));

        assertThat(db.queryClosestPort(NEAR_AA_ONE, true, null, null, true)).isNull();
        assertThat(db.queryClosestPort(NEAR_AA_ONE, true, null, null, false).portCode()).isEqualTo("AAONE");
    }

    @Test
    void getSelectedPortMatrixAppliesLoadingAndDischargeCountries() {
        var parameters = new PortParameters().setCountryPol("AA").setCountryPod("BB");

        List<PortPair> matrix = createDatabase().getSelectedPortMatrix(NEAR_AA_ONE, NEAR_CC_ONE, parameters);

        assertThat(matrix).hasSize(1);
        assertThat(matrix.get(0).originPort().portCode()).isEqualTo("AAONE");
        assertThat(matrix.get(0).destPort().portCode()).isEqualTo("BBONE");
        assertThat(matrix.get(0).originPort().share()).isEqualTo(1.0);
        assertThat(matrix.get(0).destPort().share()).isEqualTo(1.0);
    }

    @Test
    void getSelectedPortMatrixCountryRestrictedRequiresOriginPortToServeDischargeCountry() {
        var unrestricted = new PortParameters().setCountryPod("CC");
        var restricted = new PortParameters().setCountryPod("CC").setCountryRestricted(true);
        PortDatabase db = createDatabase();

        assertThat(db.getSelectedPortMatrix(NEAR_AA_ONE, NEAR_CC_ONE, unrestricted).get(0).originPort().portCode())
                .isEqualTo("AAONE");

        List<PortPair> matrix = db.getSelectedPortMatrix(NEAR_AA_ONE, NEAR_CC_ONE, restricted);
        assertThat(matrix.get(0).originPort().portCode()).isEqualTo("AATHR");
        assertThat(matrix.get(0).destPort().portCode()).isEqualTo("CCONE");
    }

    @Test
    void getSelectedPortMatrixStrictFilterWithNoMatchReturnsEmptyMatrix() {
        var parameters = new PortParameters().setCountryPol("ZZ");

        assertThat(createDatabase().getSelectedPortMatrix(NEAR_AA_ONE, NEAR_CC_ONE, parameters)).isEmpty();
    }

    @Test
    void calculateRouteIncludePortsWithCountryRestrictionRoutesFromFilteredPort() {
        TradeRouterEngine engine = createEquatorEngine();
        var options = new TradeRouterOptions()
                .setIncludePorts(true)
                .setPortParameters(new PortParameters().setCountryPod("CC").setCountryRestricted(true));

        GeoJsonFeature route = engine.calculateRoute(NEAR_AA_ONE, NEAR_CC_ONE, options);

        assertThat(route.properties().getPortOrigin().portCode()).isEqualTo("AATHR");
        assertThat(route.properties().getPortDest().portCode()).isEqualTo("CCONE");
        assertThat(route.geometry().positions().get(0)[0]).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void calculateRouteIncludePortsWithNoMatchingPortFallsBackToRawCoordinates() {
        TradeRouterEngine engine = createEquatorEngine();
        var options = new TradeRouterOptions()
                .setIncludePorts(true)
                .setAppendOriginDestination(true)
                .setPortParameters(new PortParameters().setCountryPol("ZZ"));

        GeoJsonFeature route = engine.calculateRoute(NEAR_AA_ONE, NEAR_CC_ONE, options);

        assertThat(route.properties().getPortOrigin()).isNull();
        assertThat(route.properties().getPortDest()).isNull();
        assertThat(route.geometry().positions().get(0)[0]).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void calculateRouteEmbeddedPortsHonourCountryFilters() {
        var paris = new Coordinate(2.3522, 48.8566);
        var newYork = new Coordinate(-74.0, 40.7);
        var options = new TradeRouterOptions()
                .setIncludePorts(true)
                .setPortParameters(new PortParameters().setCountryPol("BE").setCountryPod("US"));

        GeoJsonFeature route = TradeRoutes.calculate(paris, newYork, options);

        assertThat(route.properties().getPortOrigin().portCode()).startsWith("BE");
        assertThat(route.properties().getPortDest().portCode()).startsWith("US");
    }

    @Test
    void calculateRouteEmbeddedPortsCountryRestrictedOriginServesDischargeCountry() {
        var paris = new Coordinate(2.3522, 48.8566);
        var newYork = new Coordinate(-74.0, 40.7);
        var options = new TradeRouterOptions()
                .setIncludePorts(true)
                .setPortParameters(new PortParameters().setCountryPol("FR").setCountryPod("US").setCountryRestricted(true));

        GeoJsonFeature route = TradeRoutes.calculate(paris, newYork, options);

        assertThat(route.properties().getPortOrigin().portCode()).startsWith("FR");
        assertThat(route.properties().getPortOrigin().toCountries()).contains("US");
    }

    private static TradeRouterEngine createEquatorEngine() {
        MaritimeGraph graph = new MaritimeGraph();
        for (int lon = 0; lon < 11; lon++) {
            graph.addEdge(new Coordinate(lon, 0), new Coordinate(lon + 1, 0));
        }
        graph.buildIndex();
        return new DefaultTradeRouterEngine(graph, createDatabase());
    }
}
