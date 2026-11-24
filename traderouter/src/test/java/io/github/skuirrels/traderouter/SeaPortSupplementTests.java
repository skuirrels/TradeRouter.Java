package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
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

class SeaPortSupplementTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE_ROOT = "/io/github/skuirrels/traderouter/data/";

    record Entry(String code, String name, String source) {
    }

    static List<Entry> loadEntries() throws IOException {
        try (InputStream stream = TradeRouterEngine.class.getResourceAsStream(RESOURCE_ROOT + "unlocode-seaport-supplement.json")) {
            if (stream == null) {
                throw new IllegalStateException("Sea-port supplement is not embedded.");
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
        Map<String, Set<LocationFunction>> published = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (InputStream raw = TradeRouterEngine.class.getResourceAsStream(RESOURCE_ROOT + "unlocode.json.gz");
             InputStream gzip = new GZIPInputStream(raw)) {
            for (JsonNode item : JSON.readTree(gzip)) {
                published.put(item.get(0).asString(), LocationFunction.fromFlags(item.get(4).asInt()));
            }
        }
        return published;
    }

    @Test
    void entriesAreUniqueSourcedAndNameTheirCode() throws IOException {
        List<Entry> entries = loadEntries();

        assertThat(entries).hasSize(14);
        assertThat(entries).extracting(Entry::code).doesNotHaveDuplicates();
        for (Entry entry : entries) {
            UnLocode record = DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(entry.code());
            assertThat(record).as("%s must be a UN/LOCODE", entry.code()).isNotNull();
            assertThat(record.name()).as("%s must name its UN/LOCODE record", entry.code()).isEqualTo(entry.name());
            assertThat(entry.source()).as("%s must cite a source", entry.code()).isNotBlank();
        }
    }

    @Test
    void entriesAddOnlyTheSeaPortFunctionWhereUnecePublishesNone() throws IOException {
        Map<String, Set<LocationFunction>> published = loadPublishedFunctions();

        for (Entry entry : loadEntries()) {
            Set<LocationFunction> original = published.get(entry.code());
            assertThat(original)
                    .as("%s is supplemented only because UNECE records no sea-port function", entry.code())
                    .doesNotContain(LocationFunction.SEA_PORT);
            Set<LocationFunction> expected = EnumSet.noneOf(LocationFunction.class);
            expected.addAll(original);
            expected.add(LocationFunction.SEA_PORT);
            assertThat(DefaultTradeRouterEngine.getDefault().unLocodes().getByCode(entry.code()).functions())
                    .as("%s must keep its published functions", entry.code())
                    .isEqualTo(expected);
        }
    }
}
