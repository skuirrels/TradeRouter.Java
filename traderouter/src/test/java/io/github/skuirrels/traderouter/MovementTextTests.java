package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.RoadDistanceEstimate;
import io.github.skuirrels.traderouter.movements.RoadRouteProvider;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRouteStatus;
import io.github.skuirrels.traderouter.movements.RoadRoutingMode;
import io.github.skuirrels.traderouter.movements.SuppliedRoute;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MovementTextTests {

    @Test
    void toTextReportsTheCompleteMovementWithoutCallerFormatting() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.place("GBLGW"))
                .pickupTo(Waypoint.port("GBFXT"), TransportMode.ROAD)
                .thenTo(Waypoint.port("SGSIN"), TransportMode.SEA)
                .thenTo(Waypoint.port("AUMEL"), TransportMode.SEA)
                .deliverTo(Waypoint.place("AUMRS"), TransportMode.ROAD);
        MovementResult result = TradeRoutes.calculateMovement(
                plan,
                new TradeRouterOptions().setReturnPassages(true),
                12.0,
                2.0);

        String text = result.toText();

        assertThat(text).startsWith("Leg Kind");
        assertThat(text).contains("Modelled transit time");
        assertThat(text).contains("Distance basis");
        assertThat(text).contains("Pickup    Road  GBLGW  GBFXT");
        assertThat(text).contains("Main      Sea   GBFXT  SGSIN");
        assertThat(text).contains("Delivery  Road  AUMEL  AUMRS");
        assertThat(text).contains("Gibraltar, Suez, Bab-el-Mandeb, Malacca");
        assertThat(text).contains("Sunda");
        assertThat(text).contains("Modelled minimum =");
        assertThat(text).contains("sea operations");
        assertThat(text).contains("port handling");
        assertThat(text).contains("connections");
        assertThat(text).contains("planning lower bound");
        assertThat(text).contains("circuity_estimate is a planning estimate");
        assertThat(text).doesNotContain("supplied is");
        assertThat(text).contains(String.format(Locale.ROOT, "%,.0f", result.totalLength()));
        assertThat(text).contains(String.format(Locale.ROOT, "%,.1f", result.totalTransitHours()));
        assertThat(text).contains(String.format(Locale.ROOT, "%,.0f", result.totalCo2eKg()));
        assertThat(text).contains("for 12 t of cargo in 2 TEU");
        assertThat(text).contains("73.4 g per TEU-km on sea legs");
        assertThat(text).as("12 t in 2 TEU is charged at 10 t per TEU inland, in a column wide enough for the basis")
                .contains("  teu_average_weight");
        assertThat(text).contains("the greater of cargo weight and 10 t per TEU on road and rail legs");
    }

    @Test
    void toTextMatchesTheReadmeLayout() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.place("GBLGW"))
                .pickupTo(Waypoint.port("GBFXT"), TransportMode.ROAD)
                .thenTo(Waypoint.port("SGSIN"), TransportMode.SEA)
                .thenTo(Waypoint.port("AUMEL"), TransportMode.SEA)
                .deliverTo(Waypoint.place("AUMRS"), TransportMode.ROAD);
        MovementResult result = TradeRoutes.calculateMovement(
                plan, new TradeRouterOptions().setReturnPassages(true), 12.0, 2.0);

        String[] lines = result.toText().split("\n");

        assertThat(lines[0]).isEqualTo(
                "Leg Kind      Mode  From   To      Distance       Distance basis   Modelled transit time       CO2e rate  CO2e per tonne  CO2e total               Basis  Choke points");
        assertThat(lines[1]).isEqualTo(
                "                                                                                   hours      g per t-km  kg per t cargo          kg");
        assertThat(lines[2]).startsWith("1   Pickup    Road  GBLGW  GBFXT        183 km   circuity_estimate");
        assertThat(lines[2]).endsWith("457  teu_average_weight  ");
        assertThat(lines[3]).endsWith("teu  Gibraltar, Suez, Bab-el-Mandeb, Malacca");
        assertThat(lines[3]).startsWith("2   Main      Sea   GBFXT  SGSIN     15,402 km    maritime_network");
        assertThat(lines[3]).as("GLEC v3.3 sea rate, per-tonne and total figures shown in the README")
                .contains("895.2             7.3           113.0       2,261");
        assertThat(lines[4]).contains("391.6             7.3            53.6       1,072");
        assertThat(lines[6]).contains("1,306.7                           315.9       6,317");
        assertThat(lines[6]).startsWith("Total                                23,896 km");
        assertThat(lines[6]).endsWith("for 12 t of cargo in 2 TEU");
        assertThat(lines[7]).startsWith("Modelled minimum = 786.0 h travel + 376.7 h sea operations + 96 h port handling + 48 h connections = 1,306.7 h (54.4 days)");
        assertThat(lines[8]).isEqualTo(
                "Distance basis = maritime_network/road_network are network routes; circuity_estimate is a planning estimate; great_circle is straight-line");
    }

    @Test
    void toTextCanOmitColumnExplanations() {
        MovementResult result = TradeRoutes.calculateMovement("Port GBFXT to Port SGSIN Sea");

        String text = result.toText(false);

        assertThat(text).contains("GBFXT  SGSIN");
        assertThat(text).contains("Modelled minimum =");
        assertThat(text).doesNotContain("grams of CO2e emitted");
    }

    // The report for a movement with no fallback, as it was before fallback warnings were added. The road legs are
    // circuity estimates, so they carry the estimator's distance_warning, which the report does not print.
    private static final List<String> NO_FALLBACK_TABLE = List.of(
            "Leg Kind      Mode  From   To      Distance       Distance basis   Modelled transit time       CO2e rate  CO2e per tonne  CO2e total               Basis  Choke points",
            "                                                                                   hours      g per t-km  kg per t cargo          kg",
            "1   Pickup    Road  GBLGW  GBFXT        183 km   circuity_estimate                     3.0           125.0            22.9         457  teu_average_weight  ",
            "2   Main      Sea   GBFXT  SGSIN     15,402 km    maritime_network                   895.2             7.3           113.0       2,261                 teu  Gibraltar, Suez, Bab-el-Mandeb, Malacca",
            "3   Main      Sea   SGSIN  AUMEL      7,300 km    maritime_network                   391.6             7.3            53.6       1,072                 teu  Sunda",
            "4   Delivery  Road  AUMEL  AUMRS      1,011 km   circuity_estimate                    16.8           125.0           126.4       2,527  teu_average_weight  ",
            "Total                                23,896 km                                     1,306.7                           315.9       6,317                      for 12 t of cargo in 2 TEU",
            "Modelled minimum = 786.0 h travel + 376.7 h sea operations + 96 h port handling + 48 h connections = 1,306.7 h (54.4 days)");

    private static final List<String> NO_FALLBACK_NOTES = List.of(
            "Distance basis = maritime_network/road_network are network routes; circuity_estimate is a planning estimate; great_circle is straight-line",
            "Timing         = planning lower bound from configured assumptions; excludes carrier schedules, customs and disruption",
            "CO2e rate      = grams of CO2e emitted moving 1 tonne 1 km (configured factor for the mode)",
            "CO2e per tonne = rate × leg distance: kg of CO2e for each tonne of cargo carried over the leg",
            "CO2e total     = kg of CO2e for this shipment: 73.4 g per TEU-km on sea legs, the greater of cargo weight and 10 t per TEU on road and rail legs, cargo weight on air legs");

    private static final String GOOGLE_FORBIDDEN =
            "Google Routes returned HTTP 403 PERMISSION_DENIED: The caller does not have permission";

    @Test
    void toTextWithoutFallbackIsUnchanged() {
        MovementResult result = calculateNoFallbackMovement();

        assertThat(result.legs())
                .as("ordinary estimates carry the estimator's caveat, which the report leaves to the distance basis footnote")
                .anyMatch(leg -> leg.feature().properties().getDistanceWarning() != null);
        List<String> all = new ArrayList<>(NO_FALLBACK_TABLE);
        all.addAll(NO_FALLBACK_NOTES);
        assertThat(result.toText()).isEqualTo(String.join("\n", all).stripTrailing());
        assertThat(result.toText(false)).isEqualTo(String.join("\n", NO_FALLBACK_TABLE).stripTrailing());
    }

    static Stream<Arguments> roadProviderFallbacks() {
        return Stream.of(
                Arguments.of(RoadRouteStatus.UNAVAILABLE, GOOGLE_FORBIDDEN,
                        "Leg 1 warning  = google-routes returned Unavailable: Google Routes returned HTTP 403 PERMISSION_DENIED: The caller does not have permission. Estimated with a distance-decay circuity model because no road-network route was available."),
                Arguments.of(RoadRouteStatus.UNAVAILABLE, "The OSRM request timed out. ",
                        "Leg 1 warning  = google-routes returned Unavailable: The OSRM request timed out. Estimated with a distance-decay circuity model because no road-network route was available."),
                Arguments.of(RoadRouteStatus.UNAVAILABLE, "  ",
                        "Leg 1 warning  = google-routes returned Unavailable: no reason supplied. Estimated with a distance-decay circuity model because no road-network route was available."),
                // .NET trims a no-break space as whitespace, so Java does too.
                Arguments.of(RoadRouteStatus.UNAVAILABLE, "no network\u00A0",
                        "Leg 1 warning  = google-routes returned Unavailable: no network. Estimated with a distance-decay circuity model because no road-network route was available."),
                Arguments.of(RoadRouteStatus.OUTSIDE_COVERAGE, "no coverage\u2028for\r\nthis area",
                        "Leg 1 warning  = google-routes returned OutsideCoverage: no coverage for this area. Estimated with a distance-decay circuity model because no road-network route was available."));
    }

    @ParameterizedTest
    @MethodSource("roadProviderFallbacks")
    void toTextPrintsRoadProviderFallbackWarning(RoadRouteStatus status, String message, String expected) {
        MovementRequest request = new MovementRequest(
                MovementParser.parse("Pickup GBLGW to Port GBFXT Road\nPort GBFXT to Port NLRTM Sea"))
                .setRoadRouteProvider(stubProvider(RoadRouteResult.builder(status, "google-routes").message(message).build()))
                .setRoadRoutingMode(RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE);

        MovementResult result = TradeRoutes.calculateMovementAsync(request).join();

        assertThat(result.legs().get(0).feature().properties().getDistanceBasis()).isEqualTo("circuity_estimate");
        for (boolean includeExplanations : new boolean[] {true, false}) {
            List<String> lines = Arrays.asList(result.toText(includeExplanations).split("\n", -1));
            int summary = indexOfStartingWith(lines, "Modelled minimum = ");
            assertThat(lines.get(summary + 1)).as("the warning follows the legs table and its summary line").isEqualTo(expected);
            assertThat(lines).as("only the fallback leg warns").filteredOn(line -> line.contains("warning")).hasSize(1);
            if (includeExplanations) {
                assertThat(lines.get(summary + 2)).as("the warning comes before the footnotes").startsWith("Distance basis = ");
            } else {
                assertThat(lines).hasSize(summary + 2);
            }
        }
        assertThat(result.toJson()).as("the GeoJSON shape is unchanged").doesNotContainIgnoringCase("fallback");
    }

    @Test
    void toTextEstimateOnlyWithAProviderPrintsNoWarning() {
        MovementRequest request = new MovementRequest(MovementParser.parse("Pickup GBLGW to Port GBFXT Road"))
                .setRoadRouteProvider(stubProvider(RoadRouteResult.builder(RoadRouteStatus.UNAVAILABLE, "google-routes").build()))
                .setRoadRoutingMode(RoadRoutingMode.ESTIMATE_ONLY);

        MovementResult result = TradeRoutes.calculateMovementAsync(request).join();

        assertThat(result.legs().get(0).feature().properties().getDistanceWarning())
                .as("the estimate still carries its caveat").isNotNull();
        assertThat(result.toText()).doesNotContain("warning");
    }

    @Test
    void toTextTrimsTrailingWhitespaceFromAWarning() {
        MovementRequest request = new MovementRequest(MovementParser.parse("Pickup GBLGW to Port GBFXT Road"))
                .setRoadRouteProvider(stubProvider(RoadRouteResult.builder(RoadRouteStatus.UNAVAILABLE, "stub").message("down").build()))
                .setRoadRoutingMode(RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE)
                .setRoadDistanceEstimator(estimateRequest -> new RoadDistanceEstimate(200, "stub-model", "Stub warning.\n"));

        MovementResult result = TradeRoutes.calculateMovementAsync(request).join();

        assertThat(result.toText().split("\n", -1)).contains("Leg 1 warning  = stub returned Unavailable: down. Stub warning.");
    }

    @Test
    void toTextPrintsRailFallbackWarning() {
        MovementResult result = TradeRoutes.calculateMovement("DEDUI to PLWAW Rail");

        assertThat(result.toText().split("\n", -1)).contains(
                "Leg 1 warning  = DEDUI to PLWAW is outside the United States, Canada and Mexico, the only countries the embedded rail network covers. "
                        + "Great-circle distance used: a straight-line lower bound, shorter than the shortest feasible rail distance that ISO 14083 and GLEC call for. "
                        + "Supply the distance in MovementRequest.getRailRouteOverrides() where it is known.");
    }

    @Test
    void toTextPadsTheLabelLikeTheFootnotesAndNumbersEachLeg() {
        MovementResult result = TradeRoutes.calculateMovement("DEDUS to DEDUI Road\nDEDUI to PLWAW Rail");

        String[] lines = result.toText(false).split("\n", -1);
        assertThat(lines[lines.length - 1]).startsWith("Leg 2 warning  = DEDUI to PLWAW is outside the United States, Canada and Mexico");
    }

    @Test
    void onlyFallbackLegsAreMarkedAsFallbacks() {
        MovementRequest routed = new MovementRequest(
                MovementParser.parse("Pickup GBLGW to Port GBFXT Road\nPort GBFXT to DEDUI Road\nDEDUI to PLWAW Rail"))
                .setRoadRouteProvider(stubProvider(RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "stub")
                        .distanceKm(200.0).build()))
                .setRoadRoutingMode(RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE)
                .putRoadRouteOverride(2, new SuppliedRoute(900.0, "known-route"));

        MovementResult result = TradeRoutes.calculateMovementAsync(routed).join();

        assertThat(result.legs()).extracting(leg -> leg.feature().properties().isDistanceFallback())
                .as("a provider route and a supplied route are not fallbacks; the rail leg off the network is")
                .containsExactly(false, false, true);
        assertThat(TradeRoutes.calculateMovement("Pickup GBLGW to Port GBFXT Road").legs().get(0)
                .feature().properties().isDistanceFallback())
                .as("an ordinary estimate is not a fallback").isFalse();
    }

    private static MovementResult calculateNoFallbackMovement() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.place("GBLGW"))
                .pickupTo(Waypoint.port("GBFXT"), TransportMode.ROAD)
                .thenTo(Waypoint.port("SGSIN"), TransportMode.SEA)
                .thenTo(Waypoint.port("AUMEL"), TransportMode.SEA)
                .deliverTo(Waypoint.place("AUMRS"), TransportMode.ROAD);
        return TradeRoutes.calculateMovement(plan, new TradeRouterOptions().setReturnPassages(true), 12.0, 2.0);
    }

    private static int indexOfStartingWith(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError("No line starts with " + prefix);
    }

    private static RoadRouteProvider stubProvider(RoadRouteResult result) {
        return request -> CompletableFuture.completedFuture(result);
    }
}
