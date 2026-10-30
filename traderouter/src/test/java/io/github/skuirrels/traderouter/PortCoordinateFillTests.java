package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class PortCoordinateFillTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE_ROOT = "/io/github/skuirrels/traderouter/data/";
    private static final String MARKER = "Port fill, ";

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
    void rowsFillOnlySeaPortsThatNoOtherSourcePlaces() throws IOException {
        List<Row> rows = loadFillRows();
        Map<String, JsonNode> published = loadPublishedRows();

        assertThat(rows).hasSize(414);
        assertThat(rows).extracting(Row::code).doesNotHaveDuplicates();
        for (Row row : rows) {
            JsonNode original = published.get(row.code());
            assertThat(original).as("%s must be a UN/LOCODE", row.code()).isNotNull();
            assertThat(original.get(2).isNull())
                    .as("%s is filled only because UNECE publishes no coordinate", row.code())
                    .isTrue();
            assertThat(LocationFunction.fromFlags(original.get(4).asInt()))
                    .as("%s must be a sea port", row.code())
                    .contains(LocationFunction.SEA_PORT);
            assertThat(row.name()).as("%s must name its UN/LOCODE record", row.code()).isEqualTo(original.get(1).asString());
            assertThat(DefaultTradeRouterEngine.getDefault().ports().getByCodeCandidates(row.code()))
                    .as("the port list does not place %s", row.code())
                    .isEmpty();
            assertThat(row.source())
                    .as("%s must cite its source record", row.code())
                    .containsPattern("^Port fill, (World Port Index \\d+ |Wikidata Q\\d+ |GeoNames .+ geonameId \\d+,)");
        }
    }

    @Test
    void rowsResolveByCodeWithTheirSource() {
        UnLocode huelva = DefaultTradeRouterEngine.getDefault().unLocodes().getByCode("ESHUV");
        assertThat(huelva.coordinate()).isEqualTo(new Coordinate(-6.95, 37.25));
        assertThat(huelva.coordinateSource()).startsWith("Port fill, World Port Index 38220 Huelva, linked by UN/LOCODE");

        ResolvedLocation resolved = TradeRoutes.locate("ESHUV");
        assertThat(resolved.source()).isEqualTo("unlocode");
        assertThat(resolved.coordinate()).isEqualTo(huelva.coordinate());

        // A river port far from the coast is placed when its own record is confirmed in the code's province.
        UnLocode nanjing = DefaultTradeRouterEngine.getDefault().unLocodes().getByCode("CNNJG");
        assertThat(nanjing.coordinateSource())
                .startsWith("Port fill, Wikidata Q2092124 Port of Nanjing")
                .contains("in its UN/LOCODE subdivision");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "JPSAC", // Sai, Shimane: GeoNames' only Sai is in Aomori.
            "JPFAE", // Fukae, Nagasaki: its GeoNames link is Fukue, already placed as JPFUJ.
            "CNBLI", // Bali: Wikidata's and GeoNames' Bali lie outside the code's province.
            "PHTGL", // Taguilon/Ozamis: only Ozamiz, the city it is filed under, matches a source.
            "IDPAG"  // Pagatan, Kalimantan: GeoNames links Central Kalimantan, but South Kalimantan has a coastal Pagatan too.
    })
    void namesakesAreLeftUnplaced(String code) {
        assertThat(DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(code).coordinate())
                .as("%s has no position its own record supports", code)
                .isNull();
    }
}
