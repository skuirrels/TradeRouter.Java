package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.geojson.GeoJsonGeometry;
import io.github.skuirrels.traderouter.geojson.GeoJsonLineString;
import io.github.skuirrels.traderouter.geojson.GeoJsonMultiLineString;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.graph.PathResult;
import io.github.skuirrels.traderouter.movements.LegKind;
import io.github.skuirrels.traderouter.movements.Location;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.WaypointKind;
import io.github.skuirrels.traderouter.passages.Passage;
import io.github.skuirrels.traderouter.ports.AreaFeature;
import io.github.skuirrels.traderouter.ports.Port;
import io.github.skuirrels.traderouter.ports.PortDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CorrectnessRegressionTests {

    @Test
    void directedGraphBidirectionalDijkstraUsesIncomingEdgesForBackwardSearch() {
        MaritimeGraph graph = new MaritimeGraph();
        int a = graph.addNode(new Coordinate(0, 0));
        int b = graph.addNode(new Coordinate(1, 0));
        int c = graph.addNode(new Coordinate(2, 0));
        graph.addDirectedEdge(a, b, 1);
        graph.addDirectedEdge(b, c, 1);
        graph.buildIndex();

        PathResult result = graph.shortestPath(new Coordinate(0, 0), new Coordinate(2, 0));

        assertThat(result.lengthKm()).isEqualTo(2);
        assertThat(result.path()).containsExactly(new Coordinate(0, 0), new Coordinate(1, 0), new Coordinate(2, 0));
    }

    @Test
    void aStarCustomWeightsRemainsOptimalWhenWeightsAreBelowGeodesicDistance() {
        MaritimeGraph graph = new MaritimeGraph();
        int a = graph.addNode(new Coordinate(0, 0));
        int b = graph.addNode(new Coordinate(0, 10));
        int c = graph.addNode(new Coordinate(2, 0));
        graph.addDirectedEdge(a, c, 10);
        graph.addDirectedEdge(a, b, 1);
        graph.addDirectedEdge(b, c, 1);
        graph.buildIndex();

        PathResult dijkstra = graph.shortestPath(new Coordinate(0, 0), new Coordinate(2, 0), null, "dijkstra");
        PathResult astar = graph.shortestPath(new Coordinate(0, 0), new Coordinate(2, 0), null, "astar");

        assertThat(astar.lengthKm()).isEqualTo(2);
        assertThat(astar.lengthKm()).isEqualTo(dijkstra.lengthKm());
    }

    @Test
    void indexedGraphIsFrozenAgainstMutation() {
        MaritimeGraph graph = new MaritimeGraph();
        graph.addNode(new Coordinate(0, 0));
        graph.buildIndex();

        assertThat(graph.isReadOnly()).isTrue();
        assertThatThrownBy(() -> graph.addNode(new Coordinate(1, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void graphEdgeViewsExposePassagesAndIncomingEdges() {
        MaritimeGraph graph = new MaritimeGraph();
        int a = graph.addNode(new Coordinate(0, 0));
        int b = graph.addNode(new Coordinate(1, 0));
        graph.addDirectedEdge(a, b, 5, "Suez");
        graph.buildIndex();

        assertThat(graph.edgeCount()).isEqualTo(1);
        assertThat(graph.getEdges(a)).singleElement().satisfies(edge -> {
            assertThat(edge.targetNodeId()).isEqualTo(b);
            assertThat(edge.weight()).isEqualTo(5);
            assertThat(edge.passage()).isEqualTo("Suez");
        });
        assertThat(graph.getIncomingEdges(b)).singleElement().extracting(e -> e.targetNodeId()).isEqualTo(a);
        assertThat(graph.getPassage(new Coordinate(0, 0), new Coordinate(1, 0))).isEqualTo("Suez");
        assertThat(graph.shortestPath(new Coordinate(0, 0), new Coordinate(1, 0), java.util.Set.of("suez"), null).found()).isFalse();
    }

    @Test
    void portDatabaseDuplicateCodeRequiresExplicitDisambiguation() {
        Port west = newPort("XXABC", "West", -120, 0);
        Port east = newPort("XXABC", "East", 120, 0);
        PortDatabase database = new PortDatabase(List.of(west, east));

        assertThat(database.getByCodeCandidates("xxabc")).containsExactly(west, east);
        assertThatThrownBy(() -> database.getByCode("XXABC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("getByCode(code, near)");
        assertThat(database.getByCode("XXABC", new Coordinate(121, 0))).isSameAs(east);
    }

    @Test
    void portCountryFilterNormalizesSpacesAndUnderscores() {
        PortDatabase database = new PortDatabase(List.of(
                newPort("USAAA", "US", 0, 0, "United_states"),
                newPort("GBAAA", "GB", 10, 0, "United Kingdom")));

        assertThat(database.queryClosestPort(new Coordinate(9, 0), false, "United States", null, true).portCode())
                .isEqualTo("USAAA");
    }

    @Test
    void datelinePolygonContainsDatelineButNotGreenwich() {
        AreaFeature area = new AreaFeature(List.of(
                new Coordinate(170, -10), new Coordinate(-170, -10),
                new Coordinate(-170, 10), new Coordinate(170, 10)), "date-line");

        assertThat(area.contains(new Coordinate(179, 0))).isTrue();
        assertThat(area.contains(new Coordinate(-179, 0))).isTrue();
        assertThat(area.contains(new Coordinate(0, 0))).isFalse();
        assertThat(area.area()).isCloseTo(400, within(0.001));
        assertThat(area.distanceToPoint(new Coordinate(0, 0))).isGreaterThan(18000);
    }

    @Test
    void areaDistanceUsesNearestEdgeRatherThanOnlyVertices() {
        AreaFeature area = new AreaFeature(List.of(
                new Coordinate(0, 0), new Coordinate(20, 0),
                new Coordinate(20, 1), new Coordinate(0, 1)), "wide");

        assertThat(area.distanceToPoint(new Coordinate(10, -1))).isCloseTo(111.2, within(2.0));
    }

    @Test
    void invalidRouteOptionsAreRejectedBeforeRouting() {
        TradeRouterOptions options = new TradeRouterOptions().setSpeedKnots(Double.NaN).setAlgorithm("guess");

        assertThatThrownBy(() -> TradeRoutes.calculate(new Coordinate(0, 0), new Coordinate(1, 1), options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("speed");
    }

    @Test
    void unknownRoutingAlgorithmIsRejectedRatherThanFallingBack() {
        TradeRouterOptions options = new TradeRouterOptions().setAlgorithm("guess");

        assertThatThrownBy(() -> TradeRoutes.calculate(new Coordinate(0, 0), new Coordinate(1, 1), options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("algorithm");
    }

    @Test
    void unknownPassageRestrictionIsRejectedRatherThanIgnored() {
        TradeRouterOptions options = new TradeRouterOptions().setRestrictions("not_a_passage");

        assertThatThrownBy(() -> TradeRoutes.calculate(new Coordinate(0, 0), new Coordinate(1, 1), options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown passage");
    }

    @Test
    void movementParserPreservesWaypointKinds() {
        List<MovementLeg> legs = MovementParser.parse("Airport GBLHR to Port GBFXT Road");
        assertThat(legs).hasSize(1);
        MovementLeg leg = legs.get(0);

        assertThat(leg.fromKind()).isEqualTo(WaypointKind.AIRPORT);
        assertThat(leg.toKind()).isEqualTo(WaypointKind.PORT);
    }

    @Test
    void movementRejectsSemanticEndpointMismatch() {
        assertThatThrownBy(() -> TradeRoutes.calculateMovement("Airport CNSHG to Port GBFXT Road"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CNSHG")
                .hasMessageContaining("declared as Airport")
                .hasMessageContaining("sea port");
    }

    @Test
    void movementRejectsDisconnectedLegChain() {
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg(Location.fromCoordinate(new Coordinate(0, 0)), Location.fromCoordinate(new Coordinate(1, 0)), TransportMode.ROAD, LegKind.PICKUP),
                new MovementLeg(Location.fromCoordinate(new Coordinate(2, 0)), Location.fromCoordinate(new Coordinate(3, 0)), TransportMode.ROAD, LegKind.DELIVERY)));

        assertThatThrownBy(() -> DefaultTradeRouterEngine.getDefault().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("continuous chain");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY})
    void movementRejectsInvalidCargo(double cargoTonnes) {
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg(Location.fromCoordinate(new Coordinate(0, 0)), Location.fromCoordinate(new Coordinate(1, 0)), TransportMode.ROAD)))
                .setCargoTonnes(cargoTonnes);

        assertThatThrownBy(() -> DefaultTradeRouterEngine.getDefault().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cargo amount");
    }

    @Test
    void geometryFactorySplitsOrdinaryAntimeridianCrossing() {
        GeoJsonGeometry geometry = GeoJsonGeometry.fromCoordinates(List.of(
                new Coordinate(170, 10),
                new Coordinate(-170, 20)));

        assertThat(geometry).isInstanceOf(GeoJsonMultiLineString.class);
        GeoJsonMultiLineString multiLine = (GeoJsonMultiLineString) geometry;
        assertThat(multiLine.coordinates()).hasSize(2);
        assertThat(multiLine.coordinates().get(0)).hasSize(2);
        List<double[]> first = multiLine.coordinates().get(0);
        assertThat(first.get(first.size() - 1)[0]).isEqualTo(180);
        assertThat(multiLine.coordinates().get(1).get(0)[0]).isEqualTo(-180);
        assertThat(multiLine.positions()).allSatisfy(position -> assertThat(position[0]).isBetween(-180.0, 180.0));
    }

    @Test
    void geometryFactorySplitsWhenInputContainsExactPositiveBoundary() {
        GeoJsonGeometry geometry = GeoJsonGeometry.fromCoordinates(List.of(
                new Coordinate(170, 0),
                new Coordinate(180, 0),
                new Coordinate(190, 0)));

        assertThat(geometry).isInstanceOf(GeoJsonMultiLineString.class);
        GeoJsonMultiLineString multiLine = (GeoJsonMultiLineString) geometry;
        assertThat(multiLine.coordinates()).hasSize(2);
        assertThat(flatten(multiLine.coordinates().get(0))).containsExactly(170, 0, 180, 0);
        assertThat(flatten(multiLine.coordinates().get(1))).containsExactly(-180, 0, -170, 0);
    }

    @Test
    void geometryParserRoundTripsMultiLineString() {
        GeoJsonGeometry geometry = new GeoJsonMultiLineString(List.of(
                List.of(new double[] {170, 0}, new double[] {180, 0}),
                List.of(new double[] {-180, 0}, new double[] {-170, 0})));

        String json = geometry.toJson();
        GeoJsonGeometry roundTripped = GeoJsonGeometry.fromJson(json);

        assertThat(roundTripped).isInstanceOf(GeoJsonMultiLineString.class);
        assertThat(roundTripped.positions()).hasSize(4);
        assertThat(roundTripped.toJson()).isEqualTo(json);
    }

    @Test
    void lineStringDirectSerializationRemainsCompatible() {
        GeoJsonGeometry line = GeoJsonGeometry.fromCoordinates(List.of(
                new Coordinate(1, 2),
                new Coordinate(3, 4)));

        String json = line.toJson();

        assertThat(json).contains("\"type\":\"LineString\"");
        assertThat(json).contains("\"coordinates\":[[1,2],[3,4]]");
    }

    @Test
    void geometryFactoryPreservesTwoPositionsForZeroLengthRoute() {
        Coordinate point = new Coordinate(1, 2);

        GeoJsonGeometry geometry = GeoJsonGeometry.fromCoordinates(List.of(point, point));

        assertThat(geometry).isInstanceOf(GeoJsonLineString.class);
        assertThat(geometry.positions()).hasSize(2);
    }

    @Test
    void blockedRouteSerializesNullGeometry() {
        GeoJsonFeature route = TradeRoutes.calculate(
                new Coordinate(103.85457, 1.25760),
                new Coordinate(23.62904, 37.94056),
                new TradeRouterOptions().setRestrictions(Passage.SUEZ, Passage.GIBRALTAR));

        assertThat(route.geometry()).isNull();
        assertThat(route.toJson()).contains("\"geometry\":null");
    }

    @Test
    void optionsCopyIsIndependentOfTheSource() {
        TradeRouterOptions original = new TradeRouterOptions().setRestrictions(Passage.SUEZ);
        TradeRouterOptions copy = original.copy();
        copy.getRestrictions().add(Passage.PANAMA);

        assertThat(original.getRestrictions()).containsExactly(Passage.SUEZ);
        assertThat(copy.getRestrictions()).containsExactlyInAnyOrder(Passage.SUEZ, Passage.PANAMA);
    }

    private static double[] flatten(List<double[]> positions) {
        return positions.stream().flatMapToDouble(java.util.Arrays::stream).toArray();
    }

    private static Port newPort(String code, String name, double longitude, double latitude) {
        return newPort(code, name, longitude, latitude, "XX");
    }

    private static Port newPort(String code, String name, double longitude, double latitude, String country) {
        return Port.of(code, name, country, new Coordinate(longitude, latitude));
    }
}
