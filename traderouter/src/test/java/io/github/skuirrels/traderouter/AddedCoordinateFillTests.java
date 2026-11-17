package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.data.EmbeddedResources;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
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

/**
 * Supplement rows written by tools/fill-added-unlocode-coordinates.py, and the hand-reviewed rows added with them.
 */
class AddedCoordinateFillTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE_ROOT = "/io/github/skuirrels/traderouter/data/";
    private static final String MARKER = "Added fill, ";

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

    private static Map<String, JsonNode> loadPublishedRows() throws IOException {
        Map<String, JsonNode> rows = new HashMap<>();
        for (JsonNode item : readGzipResource("unlocode.json.gz")) {
            rows.put(item.get(0).asString(), item);
        }
        return rows;
    }

    @Test
    void rowsFillOnlyCodesNoOtherSourcePlacesAndLeavePortListCodesAlone() throws IOException {
        JsonNode supplement = readGzipResource("unlocode-supplement.json.gz");
        List<String> codes = new ArrayList<>();
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode item : supplement) {
            codes.add(item.get("code").asString());
            if (item.get("source").asString().startsWith(MARKER)) {
                rows.add(item);
            }
        }
        Map<String, JsonNode> published = loadPublishedRows();
        Map<String, String> aliases = EmbeddedResources.loadPortCodeAliases();

        assertThat(rows).hasSize(1_485);
        assertThat(codes).doesNotHaveDuplicates();
        for (JsonNode row : rows) {
            String code = row.get("code").asString();
            JsonNode original = published.get(code);
            assertThat(original).as("%s must be a UN/LOCODE", code).isNotNull();
            assertThat(original.get(2).isNull())
                    .as("%s is filled only because UNECE publishes no coordinate", code)
                    .isTrue();
            assertThat(row.get("name").asString()).as("%s must name its UN/LOCODE record", code).isEqualTo(original.get(1).asString());
            assertThat(DefaultTradeRouterEngine.getDefault().ports().getByCodeCandidates(code))
                    .as("the fill leaves the port list's %s alone", code)
                    .isEmpty();
            assertThat(aliases).doesNotContainKey(code);
            assertThat(aliases).doesNotContainValue(code);
            assertThat(row.get("source").asString())
                    .as("%s must cite its source", code)
                    .containsPattern("^Added fill, (GeoNames .+, geonameId \\d+|Wikidata Q\\d+ .+; GeoNames .+, geonameId \\d+, [\\d.]+ km away|OurAirports \\S+ .+, IATA [A-Z0-9]{3})");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "USSXU, -148.89, 61.71", // Sutton, Alaska: linked by GeoNames, confirmed in its subdivision.
            "USWCA, -84.25, 39.67",  // West Carrollton, Ohio.
            "ITSBM, 11.09, 44.77",   // Solara di Bomporto, Modena.
            "USLCG, -92.40, 31.09",  // Le Compte, Louisiana: the only Lecompte in the country, confirmed in its subdivision.
            "GBSYN, 0.78, 51.53"     // Shoeburyness, Essex.
    })
    void locatePlacesFilledCodes(String code, double lon, double lat) {
        ResolvedLocation resolved = TradeRoutes.locate(code);

        assertThat(resolved.source()).isEqualTo("unlocode");
        assertThat(DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(code).coordinateSource()).startsWith(MARKER);
        assertThat(Haversine.distanceKm(resolved.coordinate(), new Coordinate(lon, lat))).isLessThan(2.0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ILTAB | Wikipedia, Taba Border Crossing | 34.90 | 29.49",
            "GBDNG | GeoNames, Donnington, geonameId 13269858 | -2.44 | 52.72",
            "CNYCG | GeoNames, Yichang, geonameId 1786764 | 111.28 | 30.71",
            "CZPRB | GeoNames, Pardubice, geonameId 3068582 | 15.78 | 50.04"
    })
    void locatePlacesHandReviewedCodes(String code, String source, double lon, double lat) {
        UnLocode record = DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(code);

        assertThat(record.coordinateSource()).startsWith(source);
        assertThat(Haversine.distanceKm(TradeRoutes.locate(code).coordinate(), new Coordinate(lon, lat))).isLessThan(2.0);
    }
}
