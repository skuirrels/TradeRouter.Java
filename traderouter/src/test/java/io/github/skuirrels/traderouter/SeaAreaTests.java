package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.geojson.GeoJsonMultiLineString;
import io.github.skuirrels.traderouter.geojson.TradeRouterProperties;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.passages.Passage;
import io.github.skuirrels.traderouter.seaareas.SeaArea;
import io.github.skuirrels.traderouter.seaareas.SeaAreaInfo;
import io.github.skuirrels.traderouter.seaareas.SeaAreaTraversal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SeaAreaTests {
    // Supply Chain Intelligence sampled each path every 5 km against the polygons; the change request allows ±25 km.
    private static final double ACCEPTANCE_TOLERANCE_KM = 25.0;

    private record Expected(String id, double enterKm, double exitKm) {
    }

    static Stream<Arguments> acceptanceRoutes() {
        return Stream.of(
                Arguments.of("SAJED", "PKKHI", List.of(Passage.BABALMANDAB), List.of(
                        new Expected(SeaArea.RED_SEA, 10, 1157), new Expected(SeaArea.GULF_OF_ADEN, 1162, 2093),
                        new Expected(SeaArea.ARABIAN_SEA, 2098, 4114))),
                Arguments.of("SGSIN", "NLRTM", List.of(Passage.MALACCA, Passage.BABALMANDAB, Passage.SUEZ, Passage.GIBRALTAR), List.of(
                        new Expected(SeaArea.STRAIT_OF_SINGAPORE, 4, 52), new Expected(SeaArea.STRAIT_OF_MALACCA, 57, 1081),
                        new Expected(SeaArea.ANDAMAN_SEA, 1086, 1267), new Expected(SeaArea.BAY_OF_BENGAL, 1272, 1898),
                        new Expected(SeaArea.INDIAN_OCEAN, 1903, 2854), new Expected(SeaArea.LACCADIVE_SEA, 2859, 3771),
                        new Expected(SeaArea.ARABIAN_SEA, 3776, 6119), new Expected(SeaArea.GULF_OF_ADEN, 6124, 6994),
                        new Expected(SeaArea.RED_SEA, 6999, 8928), new Expected(SeaArea.MEDITERRANEAN_SEA, 9394, 12628),
                        new Expected(SeaArea.NORTH_ATLANTIC_OCEAN, 12973, 14624), new Expected(SeaArea.BAY_OF_BISCAY, 14629, 14685),
                        new Expected(SeaArea.ENGLISH_CHANNEL, 14690, 15285), new Expected(SeaArea.NORTH_SEA, 15291, 15497))),
                Arguments.of("CNSHA", "USLAX", List.of(), List.of(
                        new Expected(SeaArea.EAST_CHINA_SEA, 71, 468), new Expected(SeaArea.YELLOW_SEA, 474, 578),
                        new Expected(SeaArea.KOREA_STRAIT, 584, 993), new Expected(SeaArea.SEA_OF_JAPAN, 998, 2072),
                        new Expected(SeaArea.NORTH_PACIFIC_OCEAN, 2314, 10693))),
                // One North Atlantic entry, merged across the unlisted Sargasso Sea hole.
                Arguments.of("USHOU", "BEANR", List.of(), List.of(
                        new Expected(SeaArea.GULF_OF_MEXICO, 34, 1711), new Expected(SeaArea.STRAITS_OF_FLORIDA, 1713, 1896),
                        new Expected(SeaArea.NORTH_ATLANTIC_OCEAN, 1902, 8538), new Expected(SeaArea.ENGLISH_CHANNEL, 8544, 9149),
                        new Expected(SeaArea.NORTH_SEA, 9154, 9346))),
                // The change request lists only the Bosporus; the route also passes the Dardanelles.
                Arguments.of("TRIST", "EGPSD", List.of(Passage.BOSPORUS, Passage.DARDANELLES), List.of(
                        new Expected(SeaArea.AEGEAN_SEA, 270, 672), new Expected(SeaArea.MEDITERRANEAN_SEA, 797, 1472))));
    }

    @Test
    void catalogueHoldsEveryConstantOnce() throws IllegalAccessException {
        List<String> constants = new ArrayList<>();
        for (Field field : SeaArea.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()) && field.getType() == String.class) {
                constants.add((String) field.get(null));
            }
        }

        assertThat(SeaArea.getCatalogue()).hasSize(42);
        assertThat(SeaArea.getCatalogue()).extracting(SeaAreaInfo::id).containsExactlyInAnyOrderElementsOf(constants);
        assertThat(SeaArea.getCatalogue()).extracting(SeaAreaInfo::neId).doesNotHaveDuplicates();
        assertThat(SeaArea.getValidSeaAreas()).containsExactlyInAnyOrderElementsOf(constants);
    }

    @Test
    void catalogueIdsAreLowerSnakeCaseEnglishNames() {
        for (SeaAreaInfo area : SeaArea.getCatalogue()) {
            assertThat(area.id()).isEqualTo(area.name().toLowerCase(Locale.ROOT).replace(' ', '_'));
            assertThat(area.wikidata()).matches("^Q[0-9]+$");
        }
    }

    @Test
    void getReturnsCatalogueEntryAndRejectsUnknownIds() {
        assertThat(SeaArea.get("RED_SEA")).isEqualTo(new SeaAreaInfo(SeaArea.RED_SEA, "Red Sea", "Q23406", 1159115521L));
        assertThat(SeaArea.getValidSeaAreas().contains("Red_Sea")).isTrue();
        assertThatThrownBy(() -> SeaArea.get("sargasso_sea")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SeaArea.get(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("acceptanceRoutes")
    void acceptanceRouteReportsSeaAreasAndPassages(
            String origin, String destination, List<String> expectedPassages, List<Expected> expectedAreas) {
        GeoJsonFeature route = DefaultTradeRouterEngine.getDefault().calculateRoute(origin, destination, new TradeRouterOptions()
                .setAppendOriginDestination(true)
                .setReturnPassages(true)
                .setReturnSeaAreas(true));

        assertThat(route.properties().getTraversedPassages()).containsExactlyInAnyOrderElementsOf(expectedPassages);
        List<SeaAreaTraversal> areas = route.properties().getTraversedSeaAreas();
        assertThat(areas).extracting(SeaAreaTraversal::id).containsExactlyElementsOf(expectedAreas.stream().map(Expected::id).toList());
        for (int i = 0; i < expectedAreas.size(); i++) {
            SeaAreaTraversal area = areas.get(i);
            assertThat(area.enterKm()).as(area.id() + " entry").isCloseTo(expectedAreas.get(i).enterKm(), within(ACCEPTANCE_TOLERANCE_KM));
            assertThat(area.exitKm()).as(area.id() + " exit").isCloseTo(expectedAreas.get(i).exitKm(), within(ACCEPTANCE_TOLERANCE_KM));
            assertThat(area.name()).isEqualTo(SeaArea.get(area.id()).name());
            assertThat(area.wikidata()).isEqualTo(SeaArea.get(area.id()).wikidata());
        }

        assertThat(areas.get(areas.size() - 1).exitKm()).isLessThanOrEqualTo(route.properties().getLength() + 1e-6);
    }

    @Test
    void antimeridianRouteGivesSameAreasWithAndWithoutTheMultiLineStringSplit() {
        GeoJsonFeature route = DefaultTradeRouterEngine.getDefault().calculateRoute("CNSHA", "USLAX", new TradeRouterOptions()
                .setAppendOriginDestination(true)
                .setReturnSeaAreas(true));
        assertThat(route.geometry()).isInstanceOf(GeoJsonMultiLineString.class);
        GeoJsonMultiLineString split = (GeoJsonMultiLineString) route.geometry();

        List<SeaAreaTraversal> fromSplit = SeaArea.traverse(split.coordinates().stream()
                .flatMap(List::stream)
                .map(Coordinate::fromArray)
                .toList());

        List<SeaAreaTraversal> continuous = route.properties().getTraversedSeaAreas();
        assertThat(fromSplit).extracting(SeaAreaTraversal::id)
                .containsExactlyElementsOf(continuous.stream().map(SeaAreaTraversal::id).toList());
        for (int i = 0; i < continuous.size(); i++) {
            // The split adds a vertex on the antimeridian, a few kilometres off the great circle.
            assertThat(fromSplit.get(i).enterKm()).isCloseTo(continuous.get(i).enterKm(), within(5.0));
            assertThat(fromSplit.get(i).exitKm()).isCloseTo(continuous.get(i).exitKm(), within(5.0));
        }
    }

    @Test
    void returnSeaAreasOffLeavesRouteOutputUnchanged() {
        GeoJsonFeature byDefault = DefaultTradeRouterEngine.getDefault().calculateRoute("SGSIN", "NLRTM");
        GeoJsonFeature switchedOff = DefaultTradeRouterEngine.getDefault().calculateRoute("SGSIN", "NLRTM",
                new TradeRouterOptions().setReturnSeaAreas(false));

        assertThat(switchedOff.properties().getTraversedSeaAreas()).isNull();
        assertThat(switchedOff.toJson()).isEqualTo(byDefault.toJson());
        assertThat(switchedOff.toJson()).doesNotContain("traversed_sea_areas");
    }

    @Test
    void seaAreasAreWrittenToGeoJson() {
        GeoJsonFeature route = DefaultTradeRouterEngine.getDefault().calculateRoute("SAJED", "PKKHI",
                new TradeRouterOptions().setReturnSeaAreas(true));

        assertThat(route.toJson()).contains("\"traversed_sea_areas\":[{\"id\":\"red_sea\",\"name\":\"Red Sea\",\"wikidata\":\"Q23406\",\"enter_km\":");
        assertThat(route.toJson()).contains("\"exit_km\":");
    }

    @Test
    void optionsCopyKeepsReturnSeaAreas() {
        assertThat(new TradeRouterOptions().setReturnSeaAreas(true).copy().isReturnSeaAreas()).isTrue();
    }

    @Test
    void seaAreaDistancesStayInKilometresWhateverTheRouteUnits() {
        GeoJsonFeature km = DefaultTradeRouterEngine.getDefault().calculateRoute("SAJED", "PKKHI",
                new TradeRouterOptions().setReturnSeaAreas(true));
        GeoJsonFeature naut = DefaultTradeRouterEngine.getDefault().calculateRoute("SAJED", "PKKHI",
                new TradeRouterOptions().setReturnSeaAreas(true).setUnits(DistanceUnit.NAUTICAL_MILES));

        List<SeaAreaTraversal> nautAreas = naut.properties().getTraversedSeaAreas();
        assertThat(nautAreas).isEqualTo(km.properties().getTraversedSeaAreas());
        // Karachi's port position lies just outside the Arabian Sea polygon, so the last exit falls short of the end.
        assertThat(nautAreas.get(nautAreas.size() - 1).exitKm())
                .isBetween(km.properties().getLength() - 5.0, km.properties().getLength());
    }

    @Test
    void movementSeaLegCountsSeaAreasFromTheStartOfTheLeg() {
        MovementResult result = TradeRoutes.calculateMovement(
                """
                Pickup GBLGW to Port GBFXT Road
                Port GBFXT to Port CNSHG Sea
                """,
                new TradeRouterOptions().setReturnSeaAreas(true).setUnits(DistanceUnit.NAUTICAL_MILES));

        assertThat(result.legs().get(0).feature().properties().getTraversedSeaAreas()).isNull();
        TradeRouterProperties sea = result.legs().get(1).feature().properties();
        List<SeaAreaTraversal> areas = sea.getTraversedSeaAreas();
        assertThat(areas.get(0).id()).isEqualTo(SeaArea.NORTH_SEA);
        assertThat(areas.get(0).enterKm()).isLessThan(50.0);
        assertThat(areas.get(areas.size() - 1).id()).isEqualTo(SeaArea.EAST_CHINA_SEA);
        double legKm = sea.getLength() * 1.852;
        assertThat(areas.get(areas.size() - 1).exitKm()).isBetween(legKm - 100.0, legKm + 0.001);
    }
}
