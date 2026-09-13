package io.github.skuirrels.traderouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.geojson.GeoJsonMultiLineString;
import io.github.skuirrels.traderouter.passages.Passage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RoutingTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readmePortCodeRouteAcceptsOptionsWithoutCoordinateFallback() {
        GeoJsonFeature route = TradeRoutes.calculate(
                "AEJEA",
                "AGSJS",
                new TradeRouterOptions()
                        .setRestrictions(Passage.SUEZ)
                        .setReturnPassages(true));

        assertThat(route.properties().getPortOrigin().portCode()).isEqualTo("AEJEA");
        assertThat(route.properties().getPortDest().portCode()).isEqualTo("AGSJS");
        assertThat(route.properties().getTraversedPassages()).contains(Passage.ORMUZ);
        assertThat(route.properties().getTraversedPassages()).contains(Passage.SOUTH_AFRICA);
        assertThat(route.properties().getTraversedPassages()).doesNotContain(Passage.SUEZ);
    }

    @Test
    void marseilleToCapeTownNoAppendShouldMatchExpectedLength() {
        Coordinate origin = new Coordinate(5.333333, 43.333333);
        Coordinate dest = new Coordinate(18.366667, -33.916667);

        GeoJsonFeature route = TradeRoutes.calculate(origin, dest, new TradeRouterOptions().setAppendOriginDestination(false));

        assertThat(route.properties().getUnits()).isEqualTo("km");
        assertThat(route.properties().getLength()).isCloseTo(10986.505, within(1.0));
        assertThat(route.geometry().positions()).hasSize(59);

        // A* algorithm should yield identical route length
        GeoJsonFeature routeAStar = TradeRoutes.calculate(origin, dest,
                new TradeRouterOptions().setAppendOriginDestination(false).setAlgorithm("astar"));
        assertThat(routeAStar.properties().getLength()).isCloseTo(route.properties().getLength(), within(1e-3));
    }

    @Test
    void marseilleToCapeTownAppendOrigDestShouldMatchExpectedLength() {
        Coordinate origin = new Coordinate(5.333333, 43.333333);
        Coordinate dest = new Coordinate(18.366667, -33.916667);

        GeoJsonFeature route = TradeRoutes.calculate(origin, dest, new TradeRouterOptions().setAppendOriginDestination(true));

        assertThat(route.properties().getLength()).isCloseTo(10996.763, within(1.0));
        List<double[]> positions = route.geometry().positions();
        assertThat(positions).hasSize(61);

        // First coord must be origin and last must be dest
        assertThat(positions.get(0)[0]).isCloseTo(origin.longitude(), within(1e-5));
        assertThat(positions.get(0)[1]).isCloseTo(origin.latitude(), within(1e-5));
        assertThat(positions.get(positions.size() - 1)[0]).isCloseTo(dest.longitude(), within(1e-5));
        assertThat(positions.get(positions.size() - 1)[1]).isCloseTo(dest.latitude(), within(1e-5));
    }

    @Test
    void marseilleToCapeTownUnitConversionsShouldMatchExpectedOutputs() {
        Coordinate origin = new Coordinate(5.333333, 43.333333);
        Coordinate dest = new Coordinate(18.366667, -33.916667);

        GeoJsonFeature routeMiles = TradeRoutes.calculate(origin, dest, new TradeRouterOptions().setUnits(DistanceUnit.MILES));
        assertThat(routeMiles.properties().getUnits()).isEqualTo("mi");
        assertThat(routeMiles.properties().getLength()).isCloseTo(6826.70, within(1.0));
        assertThat(routeMiles.properties().getDurationHours()).isCloseTo(370.77, within(0.5));

        GeoJsonFeature routeNaut = TradeRoutes.calculate(origin, dest, new TradeRouterOptions().setUnits(DistanceUnit.NAUTICAL_MILES));
        assertThat(routeNaut.properties().getUnits()).isEqualTo("naut");
        assertThat(routeNaut.properties().getLength()).isCloseTo(5932.24, within(1.0));
        assertThat(routeNaut.properties().getDurationHours()).isCloseTo(370.77, within(0.5));
    }

    @Test
    void shanghaiToRotterdamMajorCommercialRouteShouldMatchExpectedLength() {
        Coordinate shanghai = new Coordinate(121.47, 31.23);
        Coordinate rotterdam = new Coordinate(4.48, 51.92);

        GeoJsonFeature route = TradeRoutes.calculate(shanghai, rotterdam, new TradeRouterOptions().setAppendOriginDestination(true));

        assertThat(route.properties().getLength()).isCloseTo(19646.929, within(2.0));
        assertThat(route.geometry().positions()).hasSize(159);
    }

    @Test
    void transPacificYokohamaToLosAngelesCrossesAntimeridianCorrectly() throws Exception {
        Coordinate yokohama = new Coordinate(139.64, 35.44);
        Coordinate losAngeles = new Coordinate(-118.24, 33.74);

        GeoJsonFeature route = TradeRoutes.calculate(yokohama, losAngeles, new TradeRouterOptions().setAppendOriginDestination(true));

        assertThat(route.properties().getLength()).isCloseTo(9126.579, within(2.0));
        assertThat(route.geometry()).isInstanceOf(GeoJsonMultiLineString.class);
        GeoJsonMultiLineString multiLine = (GeoJsonMultiLineString) route.geometry();
        assertThat(multiLine.coordinates()).hasSize(2);
        assertThat(multiLine.coordinates()).allSatisfy(segment -> assertThat(segment.size()).isGreaterThanOrEqualTo(2));

        for (List<double[]> segment : multiLine.coordinates()) {
            assertThat(segment.size()).isGreaterThanOrEqualTo(2);
            assertThat(segment).allSatisfy(position -> assertThat(position[0]).isBetween(-180.0, 180.0));
            for (int i = 0; i < segment.size() - 1; i++) {
                double diff = Math.abs(segment.get(i + 1)[0] - segment.get(i)[0]);
                assertThat(diff)
                        .as("each RFC 7946 line segment stays on one side of the antimeridian")
                        .isLessThanOrEqualTo(180.0);
            }
        }

        JsonNode document = JSON.readTree(route.toJson());
        assertThat(document.get("geometry").get("type").asText()).isEqualTo("MultiLineString");
    }

    @Test
    void geoJsonFeatureSerializationIsValidGeoJson() throws Exception {
        Coordinate origin = new Coordinate(5.333333, 43.333333);
        Coordinate dest = new Coordinate(18.366667, -33.916667);

        GeoJsonFeature route = TradeRoutes.calculate(origin, dest, new TradeRouterOptions().setAppendOriginDestination(true));
        String json = route.toJson();

        assertThat(json).isNotBlank();
        JsonNode root = JSON.readTree(json);

        assertThat(root.get("type").asText()).isEqualTo("Feature");
        assertThat(root.get("geometry").get("type").asText()).isEqualTo("LineString");
        assertThat(root.get("geometry").get("coordinates").size()).isEqualTo(61);
        assertThat(root.get("properties").get("units").asText()).isEqualTo("km");
        assertThat(root.get("properties").get("length").asDouble()).isCloseTo(10996.763, within(1.0));
    }

    @Test
    void indentedJsonParsesAndMatchesCompactContent() throws Exception {
        GeoJsonFeature route = TradeRoutes.calculate("FRLEH", "SGSIN", new TradeRouterOptions().setReturnPassages(true));

        JsonNode compact = JSON.readTree(route.toJson(false));
        JsonNode indented = JSON.readTree(route.toJson(true));

        assertThat(route.toJson(true)).contains("\n  \"type\": \"Feature\"");
        assertThat(indented).isEqualTo(compact);
    }
}
