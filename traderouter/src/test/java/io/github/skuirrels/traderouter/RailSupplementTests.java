package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            // Beaumont (USBPT) has no UN/LOCODE coordinate and resolves from the port list.
            assertThatCode(() -> TradeRoutes.locate(entry.code()))
                    .as("%s must resolve to a position for rail legs", entry.code())
                    .doesNotThrowAnyException();
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

    @Test
    void movementTangerMedThroughMontrealToDetroitByCodeRoutesOnTheRailNetwork() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.port("MAPTM"))
                .thenTo(Waypoint.port("CAMTR"), TransportMode.SEA)
                .thenTo(Waypoint.station("USDET"), TransportMode.RAIL);

        LegResult rail = TradeRoutes.calculateMovement(plan).legs().get(1);

        assertThat(rail.feature().properties().getDistanceBasis()).isEqualTo("rail_network");
        assertThat(rail.feature().properties().getRailroads()).contains("CN");
        assertThat(rail.length()).isBetween(850.0, 1_000.0);
    }

    @ParameterizedTest
    @CsvSource({
            "USJOT, USLAX", // Chicago-area ramp to the Los Angeles ports.
            "USCHS, USATL", // Port of Charleston inland.
            "USSLC, USOAK",
            "USEZA, USCHI", // Port Elizabeth on-dock rail to Chicago.
            "USMIA, USJAX", // FEC from PortMiami.
            "USSAT, USLRD",
            "USPIT, USCMH",
            "USEWI, USLAX", // BNSF Logistics Park Chicago.
            "USNTI, USOAK", // UP Global 2.
            "USEEU, USCHI", // BNSF Logistics Park Kansas City.
            "USV7X, USCHI", // NS Croxton.
            "USMQM, USLAX", // UP Marion, across the river from Memphis.
            "USSXT, USLAX", // UP Santa Teresa.
            "CABRP, CAMTR", // CN Brampton.
            "CAEDM, CAPRR",
            "CAREG, CAWNP",
            "CADEL, CATOR", // Deltaport on-dock rail.
            "CAMNT, CAHAL",
            "MXZLO, MXGDL", // Manzanillo on-dock rail inland to Guadalajara.
            "MXVER, MXMEX",
            "MXATM, MXMTY",
            "MXQRO, MXMEX",
            "MXTLC, MXMEX", // CPKC Puerta Mexico.
            "MXSIL, MXAGU", // Guanajuato Inland Port.
            "MXSLW, MXMTY",
            "MXCUU, MXHMO"
    })
    void movementSupplementedRampsRouteOnTheRailNetworkByCode(String origin, String destination) {
        MovementPlan plan = MovementPlan
                .from(Waypoint.station(origin))
                .thenTo(Waypoint.station(destination), TransportMode.RAIL);

        List<LegResult> legs = TradeRoutes.calculateMovement(plan).legs();

        assertThat(legs).hasSize(1);
        LegResult leg = legs.get(0);
        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("rail_network");
        assertThat(leg.feature().properties().getDistanceWarning()).isNull();
        assertThat(leg.length()).isGreaterThan(leg.feature().properties().getStraightLineLength());
    }

    @Test
    void movementAnchorageHasNoRailLinkToTheLower48() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.station("USANC"))
                .thenTo(Waypoint.station("USCHI"), TransportMode.RAIL);

        List<LegResult> legs = TradeRoutes.calculateMovement(plan).legs();

        assertThat(legs).hasSize(1);
        LegResult leg = legs.get(0);
        assertThat(leg.feature().properties().getDistanceBasis()).isNotEqualTo("rail_network");
        assertThat(leg.feature().properties().getDistanceWarning()).contains("No connected main line");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "USBNA", // Nashville: no source names a container terminal.
            "USBHM", // Birmingham: the NS terminal at McCalla is 31 km out.
            "USTUL", // Tulsa: only the bulk Port of Catoosa, 24 km out.
            "USRCX", // Rochelle: UP idled Global III in 2019.
            "MXCJS", // Ciudad Juarez: sources describe only the border crossing.
            "MXTAM", // Tampico: no source names port rail; Altamira is about 26 km out.
            "MXPBC"  // Puebla: the only named terminal dates from 2005.
    })
    void movementUnsourcedMarketsStillNeedADeclaredRailFunction(String code) {
        MovementPlan plan = MovementPlan
                .from(Waypoint.station("USCHI"))
                .thenTo(Waypoint.station(code), TransportMode.RAIL);

        assertThatThrownBy(() -> TradeRoutes.calculateMovement(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AdditionalLocationFunctions");
    }
}
