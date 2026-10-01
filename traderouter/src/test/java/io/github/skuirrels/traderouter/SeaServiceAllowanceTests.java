package io.github.skuirrels.traderouter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.movements.CorridorAllowance;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.SeaServiceAllowance;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import io.github.skuirrels.traderouter.passages.Passage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SeaServiceAllowanceTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Coordinate SHANGHAI = new Coordinate(122.07, 30.63);
    private static final Coordinate ROTTERDAM = new Coordinate(4.14, 51.95);
    private static final Coordinate GENOA = new Coordinate(8.93, 44.40);
    private static final Coordinate JEBEL_ALI = new Coordinate(55.03, 25.01);
    private static final Coordinate LOS_ANGELES = new Coordinate(-118.27, 33.73);
    private static final Coordinate NEW_YORK = new Coordinate(-74.05, 40.67);
    private static final Coordinate SINGAPORE = new Coordinate(103.85, 1.26);
    private static final Coordinate MELBOURNE = new Coordinate(144.93, -37.83);
    private static final Coordinate LAGOS = new Coordinate(3.41, 6.42);
    private static final Coordinate SANTOS = new Coordinate(-46.32, -23.97);

    static Stream<Arguments> corridorsByPassage() {
        return Stream.of(
                Arguments.of("asia-north-europe", 0.63, List.of(Passage.MALACCA, Passage.BABALMANDAB, Passage.SUEZ, Passage.GIBRALTAR)),
                Arguments.of("asia-mediterranean", 1.33, List.of(Passage.MALACCA, Passage.BABALMANDAB, Passage.SUEZ)),
                Arguments.of("gulf-europe", 1.57, List.of(Passage.ORMUZ, Passage.BABALMANDAB, Passage.SUEZ, Passage.GIBRALTAR)));
    }

    @ParameterizedTest
    @MethodSource("corridorsByPassage")
    void resolveChoosesCorridorFromPassages(String corridor, double fraction, List<String> passages) {
        CorridorAllowance resolved = SeaServiceAllowance.resolve(passages, SHANGHAI, ROTTERDAM);

        assertThat(resolved.corridor()).isEqualTo(corridor);
        assertThat(resolved.fraction()).isEqualTo(fraction);
    }

    @Test
    void resolvePanamaCorridorNeedsAnAmericanEndPoint() {
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.PANAMA), SHANGHAI, NEW_YORK))
                .isEqualTo(new CorridorAllowance("panama", 0.24));
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.PANAMA), NEW_YORK, SHANGHAI))
                .isEqualTo(new CorridorAllowance("panama", 0.24));
        // East Asia to North Europe through Panama is a Suez-avoiding Asia–Europe service, not the fitted Panama lane.
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.PANAMA), SHANGHAI, ROTTERDAM))
                .isEqualTo(new CorridorAllowance("asia-north-europe-cape", 0.22));
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.PANAMA), ROTTERDAM, SHANGHAI))
                .isEqualTo(new CorridorAllowance("asia-north-europe-cape", 0.22));
    }

    @Test
    void resolveRecognisesCapeRoutesBetweenAsiaOrTheGulfAndEurope() {
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.SUNDA, Passage.SOUTH_AFRICA), SHANGHAI, ROTTERDAM))
                .isEqualTo(new CorridorAllowance("asia-north-europe-cape", 0.22));
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.SOUTH_AFRICA, Passage.MALACCA), ROTTERDAM, SHANGHAI))
                .isEqualTo(new CorridorAllowance("asia-north-europe-cape", 0.22));
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.SUNDA, Passage.SOUTH_AFRICA, Passage.GIBRALTAR), SHANGHAI, GENOA))
                .isEqualTo(new CorridorAllowance("asia-mediterranean-cape", 0.37));
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.ORMUZ, Passage.SOUTH_AFRICA), JEBEL_ALI, ROTTERDAM))
                .isEqualTo(new CorridorAllowance("gulf-europe-cape", 0.46));

        // The Cape alone does not make a Europe corridor: West Africa and Brazil keep the default.
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.SUNDA, Passage.SOUTH_AFRICA), SHANGHAI, LAGOS))
                .isEqualTo(new CorridorAllowance("default", 0.20));
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.SUNDA, Passage.SOUTH_AFRICA), SHANGHAI, SANTOS))
                .isEqualTo(new CorridorAllowance("default", 0.20));
    }

    @Test
    void resolveReadsThePacificAndAtlanticFromTheEndPoints() {
        assertThat(SeaServiceAllowance.resolve(List.of(), SHANGHAI, LOS_ANGELES)).isEqualTo(new CorridorAllowance("transpacific", 0.12));
        assertThat(SeaServiceAllowance.resolve(List.of(), LOS_ANGELES, SHANGHAI)).isEqualTo(new CorridorAllowance("transpacific", 0.12));
        assertThat(SeaServiceAllowance.resolve(List.of(), ROTTERDAM, NEW_YORK)).isEqualTo(new CorridorAllowance("transatlantic", 0.80));
        assertThat(SeaServiceAllowance.resolve(List.of(), NEW_YORK, ROTTERDAM)).isEqualTo(new CorridorAllowance("transatlantic", 0.80));
    }

    @Test
    void resolveFallsBackToTheHistoricalDefaultForUnfittedCorridors() {
        // Intra-Asia and Asia–Oceania have no observations.
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.SUNDA), SINGAPORE, MELBOURNE)).isEqualTo(new CorridorAllowance("default", 0.20));
        assertThat(SeaServiceAllowance.resolve(List.of(), SHANGHAI, SINGAPORE)).isEqualTo(new CorridorAllowance("default", 0.20));
        // Asia to the Americas through Suez and Gibraltar is unfitted too.
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.MALACCA, Passage.SUEZ, Passage.GIBRALTAR), SHANGHAI, NEW_YORK))
                .isEqualTo(new CorridorAllowance("default", 0.20));
        // A Mediterranean origin without Suez is not the Gulf corridor.
        assertThat(SeaServiceAllowance.resolve(List.of(Passage.GIBRALTAR), GENOA, NEW_YORK)).isEqualTo(new CorridorAllowance("transatlantic", 0.80));
        assertThat(SeaServiceAllowance.resolve(null, JEBEL_ALI, SINGAPORE)).isEqualTo(new CorridorAllowance("default", 0.20));
    }

    @Test
    void movementSeaLegsReportTheirCorridorAndUseItsFraction() throws Exception {
        MovementPlan plan = MovementPlan.from(Waypoint.port("CNSHG")).thenTo(Waypoint.port("NLRTM"), TransportMode.SEA);
        MovementResult result = TradeRoutes.calculateMovement(plan);
        LegResult sea = result.legs().get(0);

        assertThat(sea.feature().properties().getOperationalAllowanceCorridor()).isEqualTo("asia-north-europe");
        assertThat(sea.feature().properties().getOperationalAllowanceFraction()).isEqualTo(0.63);
        assertThat(sea.operationalAllowanceHours()).isCloseTo(sea.durationHours() * 0.63, within(1e-9));
        assertThat(sea.feature().properties().getTraversedPassages()).as("the caller did not ask for passages").isNull();

        JsonNode doc = JSON.readTree(result.toJson());
        JsonNode properties = doc.get("features").get(0).get("properties");
        assertThat(properties.get("operational_allowance_corridor").asString()).isEqualTo("asia-north-europe");
        assertThat(properties.get("operational_allowance_fraction").asDouble()).isEqualTo(0.63);
        assertThat(properties.has("traversed_passages")).isFalse();
    }

    @Test
    void movementPassagesAreStillReturnedWhenAskedFor() {
        MovementPlan plan = MovementPlan.from(Waypoint.port("CNSHG")).thenTo(Waypoint.port("NLRTM"), TransportMode.SEA);
        MovementResult result = TradeRoutes.calculateMovement(plan, new TradeRouterOptions().setReturnPassages(true));

        assertThat(result.legs().get(0).feature().properties().getTraversedPassages()).contains(Passage.SUEZ);
        assertThat(result.legs().get(0).feature().properties().getOperationalAllowanceCorridor()).isEqualTo("asia-north-europe");
    }

    @Test
    void movementARequestFractionOverridesEveryCorridor() {
        MovementRequest request = MovementRequest.of("Port CNSHG to Port NLRTM Sea").setSeaOperationalAllowance(0.5);

        LegResult sea = DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs().get(0);

        assertThat(sea.feature().properties().getOperationalAllowanceCorridor()).isEqualTo("override");
        assertThat(sea.feature().properties().getOperationalAllowanceFraction()).isEqualTo(0.5);
        assertThat(sea.operationalAllowanceHours()).isCloseTo(sea.durationHours() * 0.5, within(1e-9));
    }

    @Test
    void movementCorridorFractionsMatchTheObservedLanesWithinFourDays() {
        // Observed port-to-port medians, berth departure to berth arrival, legs departing 2025–26.
        // Corridor fraction plus travelling time at 16 knots should land within four days of each.
        Map<List<String>, Double> observedDays = Map.of(
                List.of("CNSHG", "NLRTM"), 45.6,
                List.of("CNSHG", "ITGOA"), 53.8,
                List.of("AEJEA", "NLRTM"), 42.1,
                List.of("CNSHG", "USLAX"), 15.2,
                List.of("NLRTM", "USNYC"), 15.6);

        observedDays.forEach((lane, observed) -> {
            String from = lane.get(0);
            String to = lane.get(1);
            MovementPlan plan = MovementPlan.from(Waypoint.port(from)).thenTo(Waypoint.port(to), TransportMode.SEA);
            LegResult sea = TradeRoutes.calculateMovement(plan).legs().get(0);
            double modelledDays = (sea.durationHours() + sea.operationalAllowanceHours()) / 24.0;

            assertThat(modelledDays)
                    .as("%s to %s should follow the fitted corridor", from, to)
                    .isCloseTo(observed, within(4.0));
        });
    }

    static Stream<Arguments> suezClosures() {
        return Stream.of(
                Arguments.of(List.of(Passage.NORTHWEST, Passage.SUEZ), Passage.PANAMA),
                Arguments.of(List.of(Passage.NORTHWEST, Passage.SUEZ, Passage.PANAMA), Passage.SOUTH_AFRICA));
    }

    @ParameterizedTest
    @MethodSource("suezClosures")
    void movementAsiaToEuropeAvoidingSuezUsesTheCapeCorridor(List<String> restrictions, String expectedPassage) {
        MovementPlan plan = MovementPlan.from(Waypoint.port("CNSHG")).thenTo(Waypoint.port("NLRTM"), TransportMode.SEA);
        TradeRouterOptions options = new TradeRouterOptions().setRestrictions(restrictions).setReturnPassages(true);
        LegResult sea = TradeRoutes.calculateMovement(plan, options).legs().get(0);

        assertThat(sea.feature().properties().getTraversedPassages()).contains(expectedPassage).doesNotContain(Passage.SUEZ);
        assertThat(sea.feature().properties().getOperationalAllowanceCorridor()).isEqualTo("asia-north-europe-cape");
        assertThat(sea.feature().properties().getOperationalAllowanceFraction()).isEqualTo(0.22);
    }

    @Test
    void movementCapeCorridorFractionsMatchTheObservedLanesWithinFiveDays() {
        // The same 2025–26 observed medians, modelled on the Cape route. The two Mediterranean lanes sit
        // either side of their corridor median, which leaves each about five days out.
        Map<List<String>, Double> observedDays = Map.of(
                List.of("CNSHG", "NLRTM"), 45.5,
                List.of("CNSHG", "DEHAM"), 44.0,
                List.of("CNSHG", "ITGOA"), 53.6,
                List.of("CNSHG", "GRPIR"), 45.9,
                List.of("AEJEA", "NLRTM"), 42.2);
        TradeRouterOptions options = new TradeRouterOptions().setRestrictions(Passage.NORTHWEST, Passage.SUEZ, Passage.PANAMA);

        observedDays.forEach((lane, observed) -> {
            String from = lane.get(0);
            String to = lane.get(1);
            MovementPlan plan = MovementPlan.from(Waypoint.port(from)).thenTo(Waypoint.port(to), TransportMode.SEA);
            LegResult sea = TradeRoutes.calculateMovement(plan, options).legs().get(0);
            double modelledDays = (sea.durationHours() + sea.operationalAllowanceHours()) / 24.0;

            assertThat(sea.feature().properties().getOperationalAllowanceCorridor()).endsWith("-cape");
            assertThat(modelledDays)
                    .as("%s to %s round the Cape should follow the fitted corridor", from, to)
                    .isCloseTo(observed, within(5.0));
        });
    }
}
