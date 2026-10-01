package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.ports.AreaFeature;
import io.github.skuirrels.traderouter.ports.Port;
import io.github.skuirrels.traderouter.ports.PortDatabase;
import io.github.skuirrels.traderouter.ports.PortParameters;
import io.github.skuirrels.traderouter.ports.PortProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Ported from the PreferredPortAreaTests class in TradeRouter.Net's PortFilteringTests.cs. */
class PreferredPortAreaTests {
    private static final List<Coordinate> BELGIUM = List.of(
            new Coordinate(2.54, 51.13), new Coordinate(4.18, 50.03), new Coordinate(5.84, 49.61), new Coordinate(6.41, 50.38),
            new Coordinate(5.82, 51.12), new Coordinate(3.83, 51.62), new Coordinate(2.54, 51.13));

    private static final Coordinate BRUSSELS = new Coordinate(4.35, 50.85);
    private static final Coordinate PARIS = new Coordinate(2.35, 48.86);
    private static final Coordinate TOKYO = new Coordinate(139.68, 35.78);

    private static PortDatabase ports() {
        return DefaultTradeRouterEngine.getDefault().ports();
    }

    @Test
    void getPreferredPortsPointOutsideStrictAreaReturnsNothing() {
        var area = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("BEANR")));

        assertThat(ports().getPreferredPorts(PARIS, List.of(area))).isEmpty();
    }

    @Test
    void getPreferredPortsNonStrictAreaUsesNearestAreaWithin2000Km() {
        var area = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("BEANR")));

        List<Port> result = ports().getPreferredPorts(PARIS, List.of(area), null, false);

        assertThat(result).singleElement().extracting(Port::portCode).isEqualTo("BEANR");
    }

    @Test
    void getPreferredPortsNonStrictAreaIgnoresAreasBeyond2000Km() {
        var area = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("BEANR")));

        assertThat(ports().getPreferredPorts(TOKYO, List.of(area), null, false)).isEmpty();
    }

    @Test
    void getPreferredPortsNonStrictAreaChoosesTheCloserOfTwoAreas() {
        var belgium = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("BEANR")));
        var spain = new AreaFeature(
                List.of(new Coordinate(-9.0, 36.0), new Coordinate(3.0, 36.0), new Coordinate(3.0, 43.5), new Coordinate(-9.0, 43.5)),
                "ES", List.of(new PortProps("ESVLC")));

        List<Port> result = ports().getPreferredPorts(PARIS, List.of(spain, belgium), null, false);

        assertThat(result).singleElement().extracting(Port::portCode).isEqualTo("BEANR");
    }

    @Test
    void getPreferredPortsNestedAreasUseTheSmallestContainingArea() {
        var europe = new AreaFeature(
                List.of(new Coordinate(-10.0, 35.0), new Coordinate(30.0, 35.0), new Coordinate(30.0, 60.0), new Coordinate(-10.0, 60.0)),
                "EU", List.of(new PortProps("NLRTM")));
        var belgium = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("BEANR")));

        List<Port> result = ports().getPreferredPorts(BRUSSELS, List.of(europe, belgium));

        assertThat(result).singleElement().extracting(Port::portCode).isEqualTo("BEANR");
    }

    @Test
    void getPreferredPortsAreaWithoutPreferredPortsReturnsNothing() {
        var area = new AreaFeature(BELGIUM, "BE");

        assertThat(ports().getPreferredPorts(BRUSSELS, List.of(area))).isEmpty();
    }

    @Test
    void getPreferredPortsSharesAboveOneAreNormalisedAndSortedDescending() {
        var area = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("FRLEH", 200), new PortProps("BEANR", 250)));

        List<Port> result = ports().getPreferredPorts(BRUSSELS, List.of(area));

        assertThat(result).extracting(Port::portCode).containsExactly("BEANR", "FRLEH");
        assertThat(result.get(0).share()).isCloseTo(250.0 / 450.0, within(1e-12));
        assertThat(result.get(1).share()).isCloseTo(200.0 / 450.0, within(1e-12));
    }

    @Test
    void getPreferredPortsSharesSummingBelowOneAreKeptAsGiven() {
        var area = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("FRLEH", 0.2), new PortProps("BEANR", 0.3)));

        List<Port> result = ports().getPreferredPorts(BRUSSELS, List.of(area));

        assertThat(result.get(0).share()).isEqualTo(0.3);
        assertThat(result.get(1).share()).isEqualTo(0.2);
    }

    @Test
    void getPreferredPortsTopLimitsTheResultToTheLargestShares() {
        var area = new AreaFeature(BELGIUM, "BE",
                List.of(new PortProps("FRLEH", 1), new PortProps("BEANR", 3), new PortProps("NLRTM", 2)));

        List<Port> result = ports().getPreferredPorts(BRUSSELS, List.of(area), 2, true);

        assertThat(result).extracting(Port::portCode).containsExactly("BEANR", "NLRTM");
    }

    @Test
    void getPreferredPortsOverflowingSharesAreRejected() {
        var area = new AreaFeature(BELGIUM, "BE",
                List.of(new PortProps("FRLEH", Double.MAX_VALUE), new PortProps("BEANR", Double.MAX_VALUE)));

        assertThatThrownBy(() -> ports().getPreferredPorts(BRUSSELS, List.of(area)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'BE' overflow");
    }

    static Stream<Arguments> customCoordinateValues() {
        return Stream.of(
                Arguments.of(3.2, 51.3),
                Arguments.of(3.2f, 51.3f),
                Arguments.of(3, 51),
                Arguments.of(3L, 51L),
                Arguments.of("3.2", "51.3"));
    }

    @ParameterizedTest
    @MethodSource("customCoordinateValues")
    void getPreferredPortsUnknownPortWithXyPropsCreatesCustomPort(Object x, Object y) {
        Map<String, Object> props = new HashMap<>();
        props.put("x", x);
        props.put("y", y);
        var area = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("XXCUS", 1.0, props)));

        List<Port> result = ports().getPreferredPorts(BRUSSELS, List.of(area));

        assertThat(result).hasSize(1);
        Port port = result.get(0);
        assertThat(port.portCode()).isEqualTo("XXCUS");
        assertThat(port.name()).isEqualTo("XXCUS");
        assertThat(port.share()).isEqualTo(1.0);
        assertThat(port.coordinate().longitude()).isCloseTo(toDouble(x), within(1e-6));
        assertThat(port.coordinate().latitude()).isCloseTo(toDouble(y), within(1e-6));
    }

    static Stream<Arguments> unusableProps() {
        Map<String, Object> nullY = new HashMap<>();
        nullY.put("x", 3.2);
        nullY.put("y", null);
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(Map.of("x", 3.2)),
                Arguments.of(Map.of("x", "east", "y", 51.3)),
                Arguments.of(nullY),
                // C# decimal maps to BigDecimal; TradeRouter.Net accepts only double, float, int, long and string.
                Arguments.of(Map.of("x", new BigDecimal("3.2"), "y", new BigDecimal("51.3"))));
    }

    @ParameterizedTest
    @MethodSource("unusableProps")
    void getPreferredPortsUnknownPortWithoutUsableXyIsRejected(Map<String, Object> props) {
        var area = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("XXCUS", 1.0, props)));

        assertThatThrownBy(() -> ports().getPreferredPorts(BRUSSELS, List.of(area)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'XXCUS' in area 'BE' is not in the port list and has no x/y");
    }

    @Test
    void getPreferredPortsCustomPortWithInvalidLatitudeIsRejected() {
        var area = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("XXCUS", 1.0, Map.of("x", 3.2, "y", 95.0))));

        assertThatThrownBy(() -> ports().getPreferredPorts(BRUSSELS, List.of(area)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @ParameterizedTest
    @CsvSource({"'', 1.0", "'  ', 1.0", "BEANR, 0.0", "BEANR, -1.0", "BEANR, NaN", "BEANR, Infinity"})
    void portPropsRejectsBlankIdOrNonPositiveShare(String portId, double share) {
        assertThatThrownBy(() -> new PortProps(portId, share)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void calculateRoutesSharedAreasWithNonStrictMatchUsePreferredPortsAtBothEnds() {
        var area = new AreaFeature(BELGIUM, "BE", List.of(new PortProps("BEANR")));
        var options = new TradeRouterOptions()
                .setIncludePorts(true)
                .setPortParameters(new PortParameters().setPortsInAreas(List.of(area)).setStrictArea(false));

        List<GeoJsonFeature> routes = TradeRoutes.calculateRoutes(PARIS, TOKYO, options);

        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).properties().getPortOrigin().portCode())
                .as("Paris is outside Belgium but within 2000 km of it")
                .isEqualTo("BEANR");
        assertThat(routes.get(0).properties().getPortDest().portCode())
                .as("Tokyo is too far from Belgium and falls back to its nearest port")
                .isNotEqualTo("BEANR");
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble((String) value);
    }
}
