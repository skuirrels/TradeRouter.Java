package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.passages.Passage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PassageRestrictionTests {

    @ParameterizedTest
    @CsvSource({
            "babalmandab, Bab-el-Mandeb",
            "ormuz, Hormuz",
            "south_africa, Cape of Good Hope",
            "chili, Magellan Strait"
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
}
