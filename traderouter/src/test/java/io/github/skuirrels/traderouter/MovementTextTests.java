package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import org.junit.jupiter.api.Test;

import java.util.Locale;

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
        assertThat(text).contains("76 g per TEU-km on sea legs");
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
        assertThat(lines[2]).endsWith("337  teu_average_weight  ");
        assertThat(lines[3]).endsWith("teu  Gibraltar, Suez, Bab-el-Mandeb, Malacca");
        assertThat(lines[3]).startsWith("2   Main      Sea   GBFXT  SGSIN     15,402 km    maritime_network");
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
}
