package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.movements.LegKind;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import io.github.skuirrels.traderouter.movements.WaypointKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MovementPlanTests {

    @Test
    void fluentPlanBuildsTypedContinuousLegs() {
        MovementPlan origin = MovementPlan.from(Waypoint.place("GBLGW"));
        MovementPlan pickup = origin.pickupTo(Waypoint.port("GBFXT"), TransportMode.ROAD);
        MovementPlan plan = pickup
                .thenTo(Waypoint.port("SGSIN"), TransportMode.SEA)
                .thenTo(Waypoint.port("AUMEL"), TransportMode.SEA)
                .deliverTo(Waypoint.place("AUMRS"), TransportMode.ROAD);

        assertThat(origin.legs()).as("movement plans are immutable").isEmpty();
        assertThat(pickup.legs()).hasSize(1);
        assertThat(plan.legs()).hasSize(4);
        assertThat(plan.isComplete()).isTrue();
        assertThat(plan.legs()).extracting(MovementLeg::kind).containsExactly(
                LegKind.PICKUP, LegKind.MAIN, LegKind.MAIN, LegKind.DELIVERY);
        assertThat(plan.legs()).extracting(MovementLeg::mode).containsExactly(
                TransportMode.ROAD, TransportMode.SEA, TransportMode.SEA, TransportMode.ROAD);
        assertThat(plan.legs().get(1).from().code()).isEqualTo("GBFXT");
        assertThat(plan.legs().get(1).to().code()).isEqualTo("SGSIN");
        assertThat(plan.legs().get(1).fromKind()).isEqualTo(WaypointKind.PORT);
        assertThat(plan.legs().get(1).toKind()).isEqualTo(WaypointKind.PORT);
    }

    @Test
    void fluentPlanCalculatesThroughThePublicApi() {
        MovementPlan plan = MovementPlan
                .from(Waypoint.port("GBFXT"))
                .thenTo(Waypoint.port("SGSIN"), TransportMode.SEA);

        MovementResult result = TradeRoutes.calculateMovement(plan);

        assertThat(result.legs()).hasSize(1);
        assertThat(result.legs().get(0).length()).isGreaterThan(15_000.0);
    }

    @Test
    void fluentPlanProtectsPickupAndDeliveryOrdering() {
        MovementPlan mainCarriage = MovementPlan
                .from(Waypoint.port("GBFXT"))
                .thenTo(Waypoint.port("SGSIN"), TransportMode.SEA);
        MovementPlan completed = mainCarriage.deliverTo(Waypoint.place("SGSIN"), TransportMode.ROAD);

        assertThatThrownBy(() -> mainCarriage.pickupTo(Waypoint.port("AUMEL"), TransportMode.SEA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first leg");
        assertThatThrownBy(() -> completed.thenTo(Waypoint.port("AUMEL"), TransportMode.SEA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after the delivery leg");
    }
}
