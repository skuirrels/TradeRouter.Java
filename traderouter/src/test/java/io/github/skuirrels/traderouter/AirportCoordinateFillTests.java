package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AirportCoordinateFillTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE_ROOT = "/io/github/skuirrels/traderouter/data/";
    private static final String MARKER = "Airport fill, ";

    private record Row(String code, String name, double lon, double lat, String source) {
    }

    private static JsonNode readGzipResource(String name) throws IOException {
        try (InputStream raw = TradeRouterEngine.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (raw == null) {
                throw new IllegalStateException(name + " is not embedded.");
            }
            try (InputStream gzip = new GZIPInputStream(raw)) {
                return JSON.readTree(gzip);
            }
        }
    }

    private static List<Row> loadFillRows() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (JsonNode item : readGzipResource("unlocode-supplement.json.gz")) {
            String source = item.get("source").asString();
            if (source.startsWith(MARKER)) {
                rows.add(new Row(
                        item.get("code").asString(),
                        item.get("name").asString(),
                        item.get("lon").asDouble(),
                        item.get("lat").asDouble(),
                        source));
            }
        }
        return rows;
    }

    private static Map<String, JsonNode> loadPublishedRows() throws IOException {
        Map<String, JsonNode> rows = new HashMap<>();
        for (JsonNode item : readGzipResource("unlocode.json.gz")) {
            rows.put(item.get(0).asString(), item);
        }
        return rows;
    }

    @Test
    void rowsFillOnlyAirportsThatUneceLeavesWithoutAPosition() throws IOException {
        List<Row> rows = loadFillRows();
        Map<String, JsonNode> published = loadPublishedRows();

        assertThat(rows).hasSize(1_143);
        assertThat(rows).extracting(Row::code).doesNotHaveDuplicates();
        for (Row row : rows) {
            JsonNode original = published.get(row.code());
            assertThat(original).as("%s must be a UN/LOCODE", row.code()).isNotNull();
            assertThat(original.get(2).isNull())
                    .as("%s is filled only because UNECE publishes no coordinate", row.code())
                    .isTrue();
            assertThat(LocationFunction.fromFlags(original.get(4).asInt()))
                    .as("%s must be an airport", row.code())
                    .contains(LocationFunction.AIRPORT);
            assertThat(row.name()).as("%s must name its UN/LOCODE record", row.code()).isEqualTo(original.get(1).asString());
            assertThat(row.source())
                    .as("%s must cite the airport carrying its IATA code", row.code())
                    .matches("^Airport fill, OurAirports \\S+ .+, IATA " + row.code().substring(2) + "$");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "JPNRT, 140.39, 35.77",  // Narita.
            "USDFW, -97.04, 32.90",  // Dallas-Fort Worth.
            "CNPEK, 116.58, 40.08",  // Beijing Capital, filed by UN/LOCODE as Beijing Shoudu International Apt.
            "CAYUL, -73.74, 45.47",  // Montreal-Dorval, now Pierre Elliott Trudeau.
            "SEARN, 17.92, 59.65"    // Arlanda.
    })
    void locatePlacesMajorAirportsByCode(String code, double lon, double lat) {
        ResolvedLocation resolved = TradeRoutes.locate(code);

        assertThat(resolved.source()).isEqualTo("unlocode");
        assertThat(DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(code).coordinateSource()).startsWith(MARKER);
        assertThat(Haversine.distanceKm(resolved.coordinate(), new Coordinate(lon, lat))).isLessThan(5.0);
    }

    @Test
    void locatePlacesLatrobeInPennsylvaniaNotAtThePortListsLagunaBeach() {
        ResolvedLocation resolved = TradeRoutes.locate("USLBE");

        assertThat(resolved.source()).isEqualTo("unlocode");
        assertThat(resolved.port()).isNull();
        assertThat(Haversine.distanceKm(resolved.coordinate(), new Coordinate(-79.40, 40.28))).isLessThan(5.0);
    }

    @Test
    void movementAirLegBetweenFilledAirportsUsesGreatCircleDistance() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.airport("JPNRT"))
                .thenTo(Waypoint.airport("USDFW"), TransportMode.AIR);

        MovementResult result = TradeRoutes.calculateMovement(plan);

        assertThat(result.legs()).hasSize(1);
        LegResult leg = result.legs().get(0);
        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("great_circle");
        assertThat(leg.length()).isBetween(10_000.0, 10_400.0);
    }
}
