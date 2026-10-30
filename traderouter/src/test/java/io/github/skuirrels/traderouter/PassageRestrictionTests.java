package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.graph.GraphEdge;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.passages.Passage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PassageRestrictionTests {

    @ParameterizedTest
    @CsvSource({
            "babalmandab, Bab-el-Mandeb",
            "ormuz, Hormuz",
            "south_africa, Cape of Good Hope",
            "chili, Magellan Strait",
            "volga_don, Volga-Don Canal",
            "kiel, Kiel Canal"
    })
    void displayNameUsesRecognizableGeographicNames(String passage, String expected) {
        assertThat(Passage.getDisplayName(passage)).isEqualTo(expected);
    }

    @Test
    void validPassagesFilterShouldOnlyKeepKnownPassages() {
        List<String> input = List.of("suez", "invalid_strait", "PANAMA", "gibraltar", "suez", "dardanelles");
        List<String> valid = Passage.filterValidPassages(input);

        assertThat(valid).contains("suez", "panama", "gibraltar", "dardanelles");
        assertThat(valid).doesNotContain("invalid_strait");
        assertThat(valid).hasSize(4);
    }

    @Test
    void suezRouteTraversedPassagesMatch() {
        Coordinate origin = new Coordinate(52.99, 25.01);
        Coordinate dest = new Coordinate(-61.87, 17.15);

        GeoJsonFeature route = TradeRoutes.calculate(origin, dest, new TradeRouterOptions()
                .setAppendOriginDestination(true)
                .setRestrictions(Passage.NORTHWEST, Passage.CHILI)
                .setReturnPassages(true));

        assertThat(route.properties().getTraversedPassages()).isNotNull();
        assertThat(route.properties().getTraversedPassages())
                .containsExactlyInAnyOrder(Passage.SUEZ, Passage.ORMUZ, Passage.BABALMANDAB, Passage.GIBRALTAR);
    }

    @Test
    void restrictionPassageAvoidSuezRoutesViaSouthAfrica() {
        Coordinate origin = new Coordinate(52.99, 25.01);
        Coordinate dest = new Coordinate(-61.87, 17.15);

        GeoJsonFeature route = TradeRoutes.calculate(origin, dest, new TradeRouterOptions()
                .setAppendOriginDestination(true)
                .setRestrictions(Passage.SUEZ)
                .setReturnPassages(true));

        assertThat(route.properties().getTraversedPassages()).isNotNull();
        assertThat(route.properties().getTraversedPassages())
                .containsExactlyInAnyOrder(Passage.ORMUZ, Passage.SOUTH_AFRICA);
    }

    @Test
    void transPacificViaPanama() {
        Coordinate origin = new Coordinate(140.02, 35.51);
        Coordinate dest = new Coordinate(-97.36, 27.81);

        GeoJsonFeature route = TradeRoutes.calculate(origin, dest, new TradeRouterOptions()
                .setAppendOriginDestination(true)
                .setReturnPassages(true));

        assertThat(route.properties().getTraversedPassages()).isNotNull();
        assertThat(route.properties().getTraversedPassages()).contains(Passage.PANAMA);
    }

    @Test
    void restrictedPathsWhenBlockedReturnsNullGeometryAndZeroLength() {
        // Route from Singapore to Piraeus with both Suez and Gibraltar restricted
        Coordinate origin = new Coordinate(103.85457, 1.25760);
        Coordinate dest = new Coordinate(23.62904, 37.94056);

        GeoJsonFeature route = TradeRoutes.calculate(origin, dest, new TradeRouterOptions()
                .setRestrictions(Passage.SUEZ, Passage.GIBRALTAR)
                .setReturnPassages(true));

        assertThat(route.geometry()).isNull();
        assertThat(route.properties().getLength()).isEqualTo(0.0);
        assertThat(route.properties().getDurationHours()).isEqualTo(0.0);
    }

    @Test
    void restrictionsMatchCaseInsensitively() {
        Coordinate origin = new Coordinate(52.99, 25.01);
        Coordinate dest = new Coordinate(-61.87, 17.15);

        GeoJsonFeature route = TradeRoutes.calculate(origin, dest, new TradeRouterOptions()
                .setRestrictions("SUEZ")
                .setReturnPassages(true));

        assertThat(route.properties().getTraversedPassages()).doesNotContain(Passage.SUEZ);
        assertThat(route.properties().getTraversedPassages()).contains(Passage.SOUTH_AFRICA);
    }

    @Test
    void closingSuezOnlyRotterdamToYokohamaDoesNotOpenNorthwest() {
        GeoJsonFeature viaCodes = TradeRoutes.calculate("NLRTM", "JPYOK", new TradeRouterOptions()
                .setRestrictions(Passage.SUEZ)
                .setReturnPassages(true));
        GeoJsonFeature viaCoordinates = TradeRoutes.calculate(at("NLRTM"), at("JPYOK"), new TradeRouterOptions()
                .setRestrictions(Passage.SUEZ)
                .setReturnPassages(true));

        for (GeoJsonFeature route : List.of(viaCodes, viaCoordinates)) {
            assertThat(route.geometry()).isNotNull();
            assertThat(route.properties().getTraversedPassages())
                    .doesNotContain(Passage.NORTHWEST, Passage.BERING, Passage.SUEZ)
                    .contains(Passage.PANAMA);
        }
    }

    @Test
    void allowNorthwestRotterdamToYokohamaUsesArcticLanes() {
        GeoJsonFeature route = TradeRoutes.calculate(at("NLRTM"), at("JPYOK"), new TradeRouterOptions()
                .setReturnPassages(true)
                .setAllowNorthwest(true));

        assertThat(route.properties().getTraversedPassages()).contains(Passage.NORTHWEST);
    }

    @Test
    void allowNorthwestWhenNorthwestIsAlsoRestrictedKeepsItClosed() {
        GeoJsonFeature route = TradeRoutes.calculate("NLRTM", "JPYOK", new TradeRouterOptions()
                .setRestrictions(Passage.NORTHWEST)
                .setAllowNorthwest(true)
                .setReturnPassages(true));

        assertThat(route.properties().getTraversedPassages()).doesNotContain(Passage.NORTHWEST);
    }

    @Test
    void getClosedPassagesAddsNorthwestUnlessAllowed() {
        TradeRouterOptions options = new TradeRouterOptions().setRestrictions(Passage.SUEZ);

        assertThat(options.getRestrictions()).containsExactly(Passage.SUEZ);
        assertThat(options.getClosedPassages()).containsExactlyInAnyOrder(Passage.SUEZ, Passage.NORTHWEST);

        options.setAllowNorthwest(true);
        TradeRouterOptions copy = options.copy();
        assertThat(copy.isAllowNorthwest()).isTrue();
        assertThat(copy.getClosedPassages()).containsExactly(Passage.SUEZ);
    }

    @Test
    void volgaDonIsOpenByDefaultAndReportedOutOfTheCaspian() {
        GeoJsonFeature route = TradeRoutes.calculate("AZBAK", "RUNVS", new TradeRouterOptions().setReturnPassages(true)); // Baku to Novorossiysk

        assertThat(new TradeRouterOptions().getClosedPassages()).doesNotContain(Passage.VOLGA_DON);
        assertThat(route.geometry()).isNotNull();
        assertThat(route.properties().getTraversedPassages()).containsExactlyInAnyOrder(Passage.VOLGA_DON);
    }

    @ParameterizedTest
    @ValueSource(strings = {TradeRouterOptions.DIJKSTRA, TradeRouterOptions.ASTAR})
    void volgaDonClosedLeavesNoRouteOutOfTheCaspian(String algorithm) {
        TradeRouterOptions options = new TradeRouterOptions().setRestrictions(Passage.VOLGA_DON).setAlgorithm(algorithm);

        GeoJsonFeature route = TradeRoutes.calculate("AZBAK", "RUNVS", options);

        assertThat(route.geometry()).isNull();
        assertThat(route.properties().getLength()).isEqualTo(0.0);
        assertThatThrownBy(() -> TradeRoutes.calculateMovement("Port AZBAK to Port RUNVS Sea", options))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no sea route between AZBAK and RUNVS");
    }

    @ParameterizedTest
    @CsvSource({
            "AZBAK, RUASF", // Baku to Astrakhan, up the lower Volga
            "RURND, RUNVS", // Rostov-on-Don to Novorossiysk, down the lower Don
            "AZBAK, KZAAU"  // Baku to Aktau
    })
    void volgaDonClosedKeepsTheLowerVolgaAndDonOnTheirOwnSeas(String origin, String destination) {
        GeoJsonFeature open = TradeRoutes.calculate(origin, destination, new TradeRouterOptions().setReturnPassages(true));
        GeoJsonFeature closed = TradeRoutes.calculate(origin, destination, new TradeRouterOptions()
                .setRestrictions(Passage.VOLGA_DON)
                .setReturnPassages(true));

        assertThat(open.properties().getTraversedPassages()).isEmpty();
        assertThat(closed.geometry()).isNotNull();
        assertThat(closed.properties().getLength()).isEqualTo(open.properties().getLength());
    }

    @Test
    void everyPassageTagInTheEmbeddedNetworkIsARecognisedPassage() {
        MaritimeGraph graph = DefaultTradeRouterEngine.getDefault().graph();
        Set<String> tags = new HashSet<>();
        for (int node = 0; node < graph.nodeCount(); node++) {
            for (GraphEdge edge : graph.getEdges(node)) {
                if (edge.passage() != null) {
                    tags.add(edge.passage());
                }
            }
        }

        // Internal antimeridian seam tags are never public restrictions.
        tags.removeAll(Set.of("segment", "segment2", "pacific_ocean"));
        assertThat(tags).containsExactlyInAnyOrderElementsOf(Passage.VALID_PASSAGES);
    }

    @ParameterizedTest
    @CsvSource({
            "DEHAM, DKFRC, 350, 450, 956.3",  // Hamburg to Fredericia
            "DEHAM, PLSZZ, 450, 650, 1177.8"  // Hamburg to Szczecin
    })
    void kielIsOpenByDefaultAndShortensElbeToBalticRoutes(
            String origin, String destination, double minKm, double maxKm, double skagenKm) {
        GeoJsonFeature open = TradeRoutes.calculate(origin, destination, new TradeRouterOptions().setReturnPassages(true));
        GeoJsonFeature closed = TradeRoutes.calculate(origin, destination, new TradeRouterOptions()
                .setRestrictions(Passage.KIEL)
                .setReturnPassages(true));

        assertThat(new TradeRouterOptions().getClosedPassages()).doesNotContain(Passage.KIEL);
        assertThat(open.properties().getTraversedPassages()).containsExactlyInAnyOrder(Passage.KIEL);
        assertThat(open.properties().getLength()).isBetween(minKm, maxKm);
        assertThat(maxLatitude(open)).isLessThan(56.0);

        // Closed, the route goes back round Skagen, at 57.7 N, with its 2.6.1 length.
        assertThat(closed.properties().getTraversedPassages()).isEmpty();
        assertThat(closed.properties().getLength()).isCloseTo(skagenKm, within(0.1));
        assertThat(maxLatitude(closed)).isGreaterThan(57.5);
    }

    @ParameterizedTest
    @CsvSource({
            "NLRTM, SGSIN, 15521.4", // Rotterdam to Singapore
            "DEHAM, USNYC, 6479.9",  // Hamburg to New York
            "DEHAM, GBFXT, 688.0",   // Hamburg to Felixstowe
            "DEHAM, NLRTM, 574.9",   // Hamburg to Rotterdam
            "DEHAM, NOBGO, 1010.3",  // Hamburg to Bergen
            "PLGDN, FIHEL, 791.7",   // Gdansk to Helsinki
            "DKAAR, SESTO, 1007.3",  // Aarhus to Stockholm
            "SEGOT, NOOSL, 278.7"    // Gothenburg to Oslo
    })
    void kielLeavesRoutesThatDoNotUseItUnchanged(String origin, String destination, double expectedKm) {
        GeoJsonFeature open = TradeRoutes.calculate(origin, destination, new TradeRouterOptions().setReturnPassages(true));
        GeoJsonFeature closed = TradeRoutes.calculate(origin, destination, new TradeRouterOptions()
                .setRestrictions(Passage.KIEL)
                .setReturnPassages(true));

        assertThat(open.properties().getTraversedPassages()).doesNotContain(Passage.KIEL);
        assertThat(open.properties().getLength()).isCloseTo(expectedKm, within(0.1));
        assertThat(closed.properties().getLength()).isEqualTo(open.properties().getLength());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DEBRB", // Brunsbuettel, at the Elbe end
            "DEKEL", // Kiel, at the Baltic end
            "DEHUS", // Husum, on the North Sea
            "DETOE", // Toenning, on the North Sea
            "DEECK", // Eckernfoerde, on the Baltic
            "DESLS"  // Schleswig, on the Baltic
    })
    void kielClosedKeepsPortsBesideTheCanalOnTheirOwnSeas(String port) {
        TradeRouterOptions options = new TradeRouterOptions().setRestrictions(Passage.KIEL).setReturnPassages(true);

        GeoJsonFeature toRotterdam = TradeRoutes.calculate(port, "NLRTM", options);
        GeoJsonFeature toGdansk = TradeRoutes.calculate(port, "PLGDN", options);

        assertThat(toRotterdam.geometry()).isNotNull();
        assertThat(toGdansk.geometry()).isNotNull();
        assertThat(toRotterdam.properties().getTraversedPassages()).isEmpty();
        assertThat(toGdansk.properties().getTraversedPassages()).isEmpty();
    }

    @Test
    void kielClosedLeavesRendsburgOnTheCanalWithoutARoute() {
        GeoJsonFeature open = TradeRoutes.calculate("DEREN", "NLRTM", new TradeRouterOptions().setReturnPassages(true));
        GeoJsonFeature closed = TradeRoutes.calculate("DEREN", "NLRTM", new TradeRouterOptions().setRestrictions(Passage.KIEL));

        assertThat(open.properties().getTraversedPassages()).containsExactlyInAnyOrder(Passage.KIEL);
        assertThat(closed.geometry()).isNull();
    }

    private static double maxLatitude(GeoJsonFeature route) {
        return route.geometry().positions().stream().mapToDouble(position -> position[1]).max().orElseThrow();
    }

    private static Coordinate at(String code) {
        return TradeRoutes.locate(code).coordinate();
    }
}
