package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import io.github.skuirrels.traderouter.ports.Port;
import io.github.skuirrels.traderouter.ports.PortDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortListNamesakeTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE_ROOT = "/io/github/skuirrels/traderouter/data/";

    private static DefaultTradeRouterEngine engine() {
        return DefaultTradeRouterEngine.getDefault();
    }

    private static LegResult singleLeg(MovementResult result) {
        assertThat(result.legs()).hasSize(1);
        return result.legs().get(0);
    }

    @ParameterizedTest
    @CsvSource({
            "USPIT, -80.00, 40.44",  // The port list's USPIT is Pilottown, Louisiana.
            "USPHX, -112.07, 33.45", // Phoenix, Louisiana, on the Mississippi.
            "USBNA, -86.78, 36.17",  // Nashville, Michigan.
            "USCMH, -83.00, 39.96",  // Columbus, Georgia.
            "MXMTY, -100.32, 25.68", // A Monterrey in Chiapas.
            "CRSJO, -84.08, 9.93",   // San Jose with its longitude sign flipped.
            "PHOLO, 120.28, 14.83"   // Olongapo, placed 116 km south in Batangas.
    })
    void locatePrefersUnLocodeOverAFarAwayNamesakeInThePortList(String code, double lon, double lat) {
        ResolvedLocation resolved = TradeRoutes.locate(code);

        assertThat(resolved.source()).isEqualTo("unlocode");
        assertThat(resolved.port()).isNull();
        assertThat(Haversine.distanceKm(resolved.coordinate(), new Coordinate(lon, lat))).isLessThan(25.0);
    }

    @Test
    void locateKeepsThePortListWithin50KmAndDropsItBeyond() {
        // Cabo Negro's harbour is 49 km from UN/LOCODE's position; Winnipeg's port-list entry is 50.2 km from the city.
        ResolvedLocation caboNegro = TradeRoutes.locate("CLCNX");
        assertThat(caboNegro.source()).isEqualTo("ports");
        assertThat(Haversine.distanceKm(caboNegro.coordinate(), engine().unLocodes().getByCode("CLCNX").coordinate()))
                .isBetween(45.0, 50.0);

        assertThat(TradeRoutes.locate("CAWNP").source()).isEqualTo("unlocode");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "AEJEA", // In UN/LOCODE without a coordinate.
            "ALVLO"  // Not in UN/LOCODE at all.
    })
    void locateStillUsesThePortListWhenUnLocodeHasNoPosition(String code) {
        ResolvedLocation resolved = TradeRoutes.locate(code);

        assertThat(resolved.source()).isEqualTo("ports");
        assertThat(resolved.port()).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({
            "USAAB, Anamosa, Bell Bay",
            "USLIL, Lakeville, Lilesville",
            "CASKE, Saint-Anne-de-Kent, Sherbrooke"
    })
    void locateRejectsAPortListNamesakeWhenUnLocodeHasNoPosition(String code, String unLocodeName, String portListName) {
        assertThatThrownBy(() -> TradeRoutes.locate(code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("UN/LOCODE knows '" + code + "' as " + unLocodeName + " ")
                .hasMessageContaining("no coordinates")
                .hasMessageContaining("The port list's " + code + ", " + portListName + ", is a different place.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "FIHEL", // Helsinki, filed by UN/LOCODE as Helsingfors (Helsinki).
            "RUPRN", // Poronaysk and Poronaisk.
            "RUNIA", // Nikolaevsk on Amur and Nikolayevsk-na-Amure.
            "JPSGM", // Shiogama and Sendaishiogama.
            "SNMBA"  // M'Bao Oil Terminal and M'bao Terminal.
    })
    void locateStillUsesThePortListForASpellingVariant(String code) {
        assertThat(TradeRoutes.locate(code).source()).isEqualTo("ports");
    }

    @Test
    void locateResolvesEveryPortListCodeWithDuplicateRecords() throws IOException {
        // INNSA is listed as both Jawaharlal Nehru Port and Nhava Sheva, and UN/LOCODE has no position to choose by.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String code : portListCodes()) {
            counts.merge(code, 1, Integer::sum);
        }
        List<String> duplicated = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();

        assertThat(duplicated).contains("INNSA");
        for (String code : duplicated) {
            assertThatCode(() -> engine().locate(code))
                    .as("%s has duplicate port-list records", code)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void movementSeaLegFromNhavaShevaRoutes() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.port("INNSA"))
                .thenTo(Waypoint.port("AEJEA"), TransportMode.SEA);

        LegResult leg = singleLeg(TradeRoutes.calculateMovement(plan));

        assertThat(leg.from().port().isTerminal()).isTrue();
        assertThat(Haversine.distanceKm(leg.from().coordinate(), new Coordinate(72.95, 18.95))).isLessThan(10.0);
        assertThat(leg.length()).isBetween(1_800.0, 2_600.0);
    }

    @ParameterizedTest
    @CsvSource({
            "INNSA, AEJEA", // No UN/LOCODE position: the records are 4 km apart, so either is the port.
            "CAVAN, CNSHA"  // UN/LOCODE has a position, so the nearest record is taken.
    })
    void calculateRouteAcceptsAPortCodeWithDuplicateRecords(String origin, String destination) {
        GeoJsonFeature route = engine().calculateRoute(origin, destination);

        assertThat(route.properties().getPortOrigin().portCode()).isEqualTo(origin);
        assertThat(route.properties().getLength()).isGreaterThan(0.0);
    }

    @Test
    void duplicatedRecordsWithoutAUnLocodePositionTakeTheTerminalOrThrowWhenFarApart() {
        // ZZ is not a UN/LOCODE country, so UN/LOCODE has no position to choose between the records.
        MaritimeGraph graph = new MaritimeGraph();
        graph.addEdge(new Coordinate(0, 0), new Coordinate(1, 0));
        graph.buildIndex();
        PortDatabase ports = new PortDatabase(List.of(
                Port.of("ZZAAA", "Alpha Anchorage", "ZZ", new Coordinate(0.00, 0.0)),
                Port.builder().portCode("ZZAAA").name("Alpha Terminal").country("ZZ")
                        .coordinate(new Coordinate(0.03, 0.0)).terminalFlag(1.0).build(),
                Port.of("ZZBBB", "Bravo", "ZZ", new Coordinate(0.0, 0.0)),
                Port.of("ZZBBB", "Bravo", "ZZ", new Coordinate(2.0, 0.0))));
        DefaultTradeRouterEngine custom = new DefaultTradeRouterEngine(graph, ports);

        ResolvedLocation alpha = custom.locate("zzaaa");
        assertThat(alpha.source()).isEqualTo("ports");
        assertThat(alpha.port().name()).as("records 3 km apart are one port, so the terminal is taken")
                .isEqualTo("Alpha Terminal");

        String farApart = "Port code 'ZZBBB' has 2 port-list records up to 222 km apart, and UN/LOCODE publishes no "
                + "position to choose between them. Supply the location as a coordinate.";
        assertThatThrownBy(() -> custom.locate("ZZBBB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(farApart);
        assertThatThrownBy(() -> custom.calculateRoute("ZZBBB", "ZZAAA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(farApart);
    }

    private static List<String> portListCodes() throws IOException {
        List<String> codes = new ArrayList<>();
        try (InputStream raw = TradeRouterEngine.class.getResourceAsStream(RESOURCE_ROOT + "ports.json.gz")) {
            if (raw == null) {
                throw new IllegalStateException("Port list is not embedded.");
            }
            try (InputStream gzip = new GZIPInputStream(raw)) {
                for (JsonNode port : JSON.readTree(gzip)) {
                    codes.add(port.get("port").asString());
                }
            }
        }
        return codes;
    }

    @Test
    void locateNeverTakesAPortListPositionMoreThan50KmFromUnLocode() throws IOException {
        // Guards the rule across every port-list code, including any added by a later data update.
        Set<String> codes = new LinkedHashSet<>(portListCodes());

        assertThat(codes).hasSizeGreaterThan(3000);
        for (String code : codes) {
            UnLocode record = engine().unLocodes().getByCode(code);
            if (record == null || record.coordinate() == null) {
                continue;
            }
            ResolvedLocation resolved = engine().locate(code);
            if (resolved.source().equals("ports")) {
                assertThat(Haversine.distanceKm(resolved.coordinate(), record.coordinate()))
                        .as("%s must not resolve to a namesake", code)
                        .isLessThanOrEqualTo(50.0);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "ASPPG, -170.67, -14.28", // UNECE puts Pago Pago in the northern hemisphere.
            "CDBNW, 12.38, -5.98",    // And Banana, Congo.
            "ESSCI, -7.43, 43.70",    // GeoNames linked San Ciprian to Aldan, 194 km south.
            "USHPZ, -80.01, 35.96",   // UNECE puts High Point, North Carolina, in West Virginia.
            "GRRAF, 24.01, 38.02",    // GeoNames linked a hamlet of Rafina in Thessaly, not the Attica ferry port.
            "VELEC, -71.52, 10.70"    // GeoNames linked a La Estacada in Apure, not the one on Lake Maracaibo.
    })
    void correctionsPlaceCodesWhereTheirOwnRecordsPutThem(String code, double lon, double lat) {
        UnLocode record = engine().unLocodes().getByCode(code);

        assertThat(Haversine.distanceKm(record.coordinate(), new Coordinate(lon, lat))).isLessThan(2.0);
        assertThat(record.coordinateSource())
                .containsPattern("^(World Port Index \\d+ .+, linked by UN/LOCODE|GeoNames, .+, geonameId \\d+.*); replaces ");
        assertThat(TradeRoutes.locate(code).source())
                .as("the corrected position agrees with the port list")
                .isEqualTo("ports");
    }

    @Test
    void movementSeaLegFromRafinaLeavesFromAttica() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.port("GRRAF"))
                .thenTo(Waypoint.port("GRPIR"), TransportMode.SEA);

        LegResult leg = singleLeg(TradeRoutes.calculateMovement(plan));

        assertThat(leg.length()).as("Rafina and Piraeus are both on the Attica coast").isLessThan(200.0);
    }

    @Test
    void movementSeaLegFromOlongapoLeavesFromSubicBay() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.port("PHOLO"))
                .thenTo(Waypoint.port("PHMNL"), TransportMode.SEA);

        LegResult leg = singleLeg(TradeRoutes.calculateMovement(plan));

        assertThat(leg.from().source()).isEqualTo("unlocode");
        assertThat(leg.length()).isBetween(80.0, 200.0);
    }

    @Test
    void movementChicagoToPhoenixRoutesToArizona() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.place("USCHI"))
                .thenTo(Waypoint.place("USPHX"), TransportMode.RAIL);
        MovementRequest request = new MovementRequest(List.copyOf(plan.legs()))
                .addLocationFunctions("USPHX", LocationFunction.RAIL_TERMINAL);

        LegResult leg = singleLeg(TradeRoutes.calculateMovement(request));

        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("rail_network");
        assertThat(leg.length()).isBetween(2_700.0, 3_200.0);
    }

    @Test
    void movementMonterreyToLaredoRoutesOnTheRailNetwork() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.station("MXMTY"))
                .thenTo(Waypoint.station("USLRD"), TransportMode.RAIL);

        LegResult leg = singleLeg(TradeRoutes.calculateMovement(plan));

        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("rail_network");
        assertThat(leg.feature().properties().getRailroads()).contains("CPKC");
        assertThat(leg.length()).isBetween(250.0, 400.0);
    }

    // UN/LOCODE files these as airports, under the IATA code the port list uses for the city or its port.
    @ParameterizedTest
    @CsvSource({
            "CNNGB, 121.462, 29.827",  // Ningbo Lishe; the port list's CNNGB is the port of Ningbo, 11 km away.
            "CNSHA, 121.336, 31.198",  // Shanghai Hongqiao, 27 km from the port list's Shanghai.
            "CNTAO, 120.088, 36.362",  // Qingdao Jiaodong, 42 km from the port list's Qingdao.
            "USJFK, -73.779, 40.640",  // John F. Kennedy, 39 km from the port list's position.
            "BHBAH, 50.638, 26.267"    // Bahrain International Airport.
    })
    void locatePrefersUnLocodeWhenItRecordsANamedAirport(String code, double lon, double lat) {
        ResolvedLocation resolved = TradeRoutes.locate(code);

        assertThat(resolved.source()).isEqualTo("unlocode");
        assertThat(resolved.port()).isNull();
        assertThat(Haversine.distanceKm(resolved.coordinate(), new Coordinate(lon, lat))).isLessThan(2.0);
    }

    @Test
    void locateKeepsThePortListForAnAirportWithACityName() {
        // UN/LOCODE files USATL as Atlanta, an airport without "Apt" in its name, so the port list's Atlanta stands.
        assertThat(TradeRoutes.locate("USATL").source()).isEqualTo("ports");
    }

    @Test
    void movementAirLegFromNingboAirportStartsAtTheAirport() {
        List<LegResult> legs = TradeRoutes.calculateMovement("CNNGB to GBLHR Air").legs();

        assertThat(legs).hasSize(1);
        assertThat(Haversine.distanceKm(legs.get(0).from().coordinate(), new Coordinate(121.462, 29.827))).isLessThan(1.0);
    }

    @Test
    void movementSeaLegFromAnAirportCodeDeclaredASeaPortStartsAtThePortListPort() {
        // Declared in lower case: the map is keyed case-insensitively, as in .NET.
        MovementRequest request = new MovementRequest(MovementParser.parse("CNNGB to NLRTM Sea"))
                .addLocationFunctions("cnngb", LocationFunction.SEA_PORT);

        LegResult leg = DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs().get(0);

        assertThat(leg.from().source()).as("the caller declared CNNGB a sea port, so it means the port of Ningbo").isEqualTo("ports");
        assertThat(leg.feature().properties().getPortOrigin().name()).isEqualTo("Ningbo");
    }

    @Test
    void movementAirLegFromAnAirportCodeDeclaredAnotherFunctionStaysAtTheAirport() {
        // Only the sea-port function means the port; any other declaration leaves the code at the airport.
        MovementRequest request = new MovementRequest(MovementParser.parse("USJFK to GBLHR Air"))
                .addLocationFunctions("USJFK", LocationFunction.AIRPORT, LocationFunction.RAIL_TERMINAL);

        LegResult leg = DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs().get(0);

        assertThat(leg.from().source()).isEqualTo("unlocode");
        assertThat(Haversine.distanceKm(leg.from().coordinate(), new Coordinate(-73.779, 40.640))).isLessThan(2.0);
    }

    @Test
    void movementCoordinateOverrideForAnAirportCodeIsUsedAsGiven() {
        Coordinate supplied = new Coordinate(121.5, 29.9);
        MovementRequest request = new MovementRequest(MovementParser.parse("CNNGB to GBLHR Air"))
                .putCoordinate("CNNGB", supplied);

        LegResult leg = DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs().get(0);

        assertThat(leg.from().source()).isEqualTo("coordinates");
        assertThat(leg.from().coordinate()).isEqualTo(supplied);
    }

    @Test
    void calculateRouteStillRoutesAnAirportCodeFromThePortList() {
        // The port-code route API reads the port list alone, so CNNGB stays the port of Ningbo there.
        GeoJsonFeature route = TradeRoutes.calculate("CNNGB", "NLRTM");

        assertThat(route.properties().getPortOrigin().name()).isEqualTo("Ningbo");
    }
}
