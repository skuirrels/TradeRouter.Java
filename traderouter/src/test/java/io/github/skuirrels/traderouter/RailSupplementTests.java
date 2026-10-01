package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RailSupplementTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE_ROOT = "/io/github/skuirrels/traderouter/data/";

    private record Entry(String code, String name, String source) {
    }

    private static List<Entry> loadEntries() throws IOException {
        try (InputStream stream = TradeRouterEngine.class.getResourceAsStream(RESOURCE_ROOT + "unlocode-rail-supplement.json")) {
            if (stream == null) {
                throw new IllegalStateException("Rail supplement is not embedded.");
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

    private static Map<String, Set<LocationFunction>> loadPublishedFunctions() throws IOException {
        try (InputStream raw = TradeRouterEngine.class.getResourceAsStream(RESOURCE_ROOT + "unlocode.json.gz");
             InputStream gzip = new GZIPInputStream(raw)) {
            Map<String, Set<LocationFunction>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (JsonNode item : JSON.readTree(gzip)) {
                result.put(item.get(0).asString(), LocationFunction.fromFlags(item.get(4).asInt()));
            }
            return result;
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
            assertThat(record.coordinate()).as("%s must resolve to a position for rail legs", entry.code()).isNotNull();
            assertThat(entry.source()).as("%s must cite a source", entry.code()).contains("https://");
        }
    }

    @Test
    void entriesAddOnlyTheRailFunctionWhereUnecePublishesNone() throws IOException {
        Map<String, Set<LocationFunction>> published = loadPublishedFunctions();

        for (Entry entry : loadEntries()) {
            Set<LocationFunction> original = published.get(entry.code());
            assertThat(original)
                    .as("%s is supplemented only because UNECE records no rail function", entry.code())
                    .doesNotContain(LocationFunction.RAIL_TERMINAL, LocationFunction.MULTIMODAL);
            Set<LocationFunction> expected = EnumSet.noneOf(LocationFunction.class);
            expected.addAll(original);
            expected.add(LocationFunction.RAIL_TERMINAL);
            assertThat(DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(entry.code()).functions())
                    .as("%s must keep its published functions", entry.code())
                    .isEqualTo(expected);
        }
    }

    @Test
    void movementLosAngelesToChicagoByCodeRoutesOnTheRailNetwork() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.station("USLAX"))
                .thenTo(Waypoint.station("USCHI"), TransportMode.RAIL);

        List<LegResult> legs = TradeRoutes.calculateMovement(plan).legs();

        assertThat(legs).hasSize(1);
        LegResult leg = legs.get(0);
        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("rail_network");
        assertThat(leg.length()).isBetween(3_300.0, 3_800.0);
    }
}
