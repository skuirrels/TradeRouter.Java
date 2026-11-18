package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AirportSupplementTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE_ROOT = "/io/github/skuirrels/traderouter/data/";

    record Entry(String code, String name, String source) {
    }

    static List<Entry> loadEntries() throws IOException {
        try (InputStream stream = TradeRouterEngine.class.getResourceAsStream(RESOURCE_ROOT + "unlocode-airport-supplement.json")) {
            if (stream == null) {
                throw new IllegalStateException("Airport supplement is not embedded.");
            }
            List<Entry> entries = new ArrayList<>();
            for (JsonNode item : JSON.readTree(stream)) {
                entries.add(new Entry(
                        item.get("code").asString(),
                        item.get("name").asString(),
                        item.get("source").asString()));
            }
            return entries;
        }
    }

    @Test
    void entriesAreUniqueSourcedAndNameTheirCode() throws IOException {
        List<Entry> entries = loadEntries();

        assertThat(entries).isNotEmpty();
        assertThat(entries).extracting(Entry::code).doesNotHaveDuplicates();
        for (Entry entry : entries) {
            UnLocode record = DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(entry.code());
            assertThat(record).as("%s must be a UN/LOCODE", entry.code()).isNotNull();
            assertThat(record.name()).as("%s must name its UN/LOCODE record", entry.code()).isEqualTo(entry.name());
            assertThat(entry.source()).as("%s must cite a source", entry.code()).contains("https://");
        }
    }

    @Test
    void entriesAddOnlyTheAirportFunctionWherePublishedRowsLackIt() throws IOException {
        Map<String, Set<LocationFunction>> published = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (InputStream raw = TradeRouterEngine.class.getResourceAsStream(RESOURCE_ROOT + "unlocode.json.gz");
             InputStream gzip = new GZIPInputStream(raw)) {
            for (JsonNode item : JSON.readTree(gzip)) {
                published.put(item.get(0).asString(), LocationFunction.fromFlags(item.get(4).asInt()));
            }
        }
        Set<String> railTerminals = new HashSet<>();
        RailSupplementTests.loadEntries().forEach(entry -> railTerminals.add(entry.code()));

        for (Entry entry : loadEntries()) {
            Set<LocationFunction> original = published.get(entry.code());
            assertThat(original)
                    .as("%s is supplemented only because its row records no airport", entry.code())
                    .doesNotContain(LocationFunction.AIRPORT);
            Set<LocationFunction> expected = EnumSet.noneOf(LocationFunction.class);
            expected.addAll(original);
            expected.add(LocationFunction.AIRPORT);
            if (railTerminals.contains(entry.code())) {
                expected.add(LocationFunction.RAIL_TERMINAL);
            }
            assertThat(DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(entry.code()).functions())
                    .as("%s must keep its published functions", entry.code())
                    .isEqualTo(expected);
        }
    }

    @Test
    void ningboTakesTheFunctionsUnece20251Lists() {
        UnLocode ningbo = DefaultTradeRouterEngine.getDefault().unLocodes().getByCode("CNNBO");

        assertThat(ningbo.functions()).containsExactlyInAnyOrder(
                LocationFunction.SEA_PORT, LocationFunction.RAIL_TERMINAL, LocationFunction.AIRPORT);
        assertThat(ningbo.coordinate()).isNotNull();
    }

    @ParameterizedTest
    // UNECE 2025-1 lists Helsinki and Kaohsiung as airports; the port list keeps their positions. MIL is the IATA
    // code for all Milan airports; the code stays in the city.
    @ValueSource(strings = {"FIHEL", "TWKHH", "ITMIL"})
    void movementCityCodeWithASupplementedAirportCanEndAnAirLeg(String code) {
        assertThat(DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(code).isAirport()).isTrue();

        List<LegResult> legs = TradeRoutes.calculateMovement(code + " to DEFRA Air").legs();

        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).from().source()).isEqualTo("ports");
        assertThat(legs.get(0).feature().properties().getDistanceBasis()).isEqualTo("great_circle");
        assertThat(legs.get(0).length()).isGreaterThan(400.0);
    }

    @Test
    void movementHelsinkiAndKaohsiungStillEndSeaLegsAtTheirPorts() {
        List<LegResult> legs = TradeRoutes.calculateMovement("FIHEL to TWKHH Sea").legs();

        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).feature().properties().getDistanceBasis()).isEqualTo("maritime_network");
    }

    @Test
    void movementNingboCanBeASeaPortStationAndAirport() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.station("CNXIA"))
                .thenTo(Waypoint.station("CNNBO"), TransportMode.RAIL)
                .thenTo(Waypoint.port("NLRTM"), TransportMode.SEA);

        MovementResult movement = TradeRoutes.calculateMovement(plan);

        assertThat(movement.legs().get(0).feature().properties().getDistanceBasis()).isEqualTo("great_circle");
        assertThat(movement.legs().get(1).feature().properties().getDistanceBasis()).isEqualTo("maritime_network");
        List<LegResult> airLegs = TradeRoutes.calculateMovement("CNNBO to GBLHR Air").legs();
        assertThat(airLegs).hasSize(1);
        LegResult air = airLegs.get(0);
        assertThat(air.from().code()).isEqualTo("CNNBO");
        assertThat(Haversine.distanceKm(air.from().coordinate(), new Coordinate(121.55, 29.86667)))
                .as("Ningbo keeps its UNECE position").isLessThan(0.1);
        assertThat(air.feature().properties().getDistanceBasis()).isEqualTo("great_circle");
    }
}
