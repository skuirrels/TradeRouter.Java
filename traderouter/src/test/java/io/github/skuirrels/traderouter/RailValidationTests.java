package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.SuppliedRoute;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RailValidationTests {
    @ParameterizedTest
    @ValueSource(strings = {"INTMX to INTKD Rail", "Depot INTMX to Station INTKD Rail"})
    void railAcceptsMultimodalFacility(String legs) {
        MovementResult result = TradeRoutes.calculateMovement(legs);

        assertThat(result.legs()).hasSize(1);
        assertThat(result.legs().get(0).length()).isGreaterThan(0.0);
    }

    @Test
    void railRejectionNamesAdditionalLocationFunctions() {
        assertThatThrownBy(() -> TradeRoutes.calculateMovement("GBFXT to DEDUI Rail"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Leg 1 uses Rail, but its from location GBFXT is recorded as sea port rather than a compatible rail terminal. "
                        + "If the UN/LOCODE record is incomplete, declare the function in MovementRequest.getAdditionalLocationFunctions().");
    }

    @Test
    void railAdditionalLocationFunctionsAcceptDeclaredRailTerminal() {
        // Maslianico is recorded only as a road terminal; the caller's own sources are assumed to show a rail siding.
        MovementPlan plan = MovementPlan
                .from(Waypoint.station("ITMLN"))
                .thenTo(Waypoint.station("DEDUI"), TransportMode.RAIL);
        MovementRequest request = new MovementRequest(List.copyOf(plan.legs()));

        assertThatThrownBy(() -> DefaultTradeRouterEngine.getDefault().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ITMLN is declared as Station, but UN/LOCODE records road terminal.")
                .hasMessageEndingWith("getAdditionalLocationFunctions().");

        request.addLocationFunctions("itmln", LocationFunction.RAIL_TERMINAL);
        List<LegResult> legs = DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs();

        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).feature().properties().getFrom()).isEqualTo("ITMLN");
        assertThat(legs.get(0).length()).isGreaterThan(500.0);
    }

    @Test
    void railAdditionalLocationFunctionsDoNotAffectOtherCodes() {
        MovementRequest request = new MovementRequest(MovementParser.parse("GBFXT to DEDUI Rail"));
        request.addLocationFunctions("ITMLN", LocationFunction.RAIL_TERMINAL);

        assertThatThrownBy(() -> DefaultTradeRouterEngine.getDefault().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Leg 1 uses Rail, but its from location GBFXT");
    }

    // The .NET "unknown additional function flag" case casts 256 to the LocationFunctions flags enum. A Java
    // Set<LocationFunction> cannot hold an unknown flag, so the nearest invalid value it can carry, a null
    // element, takes its place and is rejected by the same validation step.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "rail override on road leg | Rail route override sequence 1 does not refer to a rail leg.",
            "rail override past end | Rail route override sequence 3 is outside the movement's leg range.",
            "rail override negative distance | Rail route override sequence 2 has an invalid distance.",
            "road override on rail leg | Road route override sequence 2 does not refer to a road leg.",
            "blank additional function key | Additional location function keys cannot be blank.",
            "null additional function | Additional location functions for ITMLN cannot be null."
    })
    void railRejectsInvalidRequest(String invalidRequest, String expectedMessage) {
        MovementRequest request = new MovementRequest(MovementParser.parse("Pickup GBLGW to GBFXT Road\nDEHAM to DEDUI Rail"));
        switch (invalidRequest) {
            case "rail override on road leg" -> request.putRailRouteOverride(1, new SuppliedRoute(10, null));
            case "rail override past end" -> request.putRailRouteOverride(3, new SuppliedRoute(10, null));
            case "rail override negative distance" -> request.putRailRouteOverride(2, new SuppliedRoute(-1, null));
            case "road override on rail leg" -> request.putRoadRouteOverride(2, new SuppliedRoute(10, null));
            case "blank additional function key" -> request.addLocationFunctions(" ", LocationFunction.RAIL_TERMINAL);
            case "null additional function" -> {
                Set<LocationFunction> functions = new HashSet<>(Arrays.asList(LocationFunction.RAIL_TERMINAL, null));
                request.getAdditionalLocationFunctions().put("ITMLN", functions);
            }
            default -> throw new IllegalArgumentException("Unknown case: " + invalidRequest);
        }

        assertThatThrownBy(() -> DefaultTradeRouterEngine.getDefault().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(expectedMessage);
    }
}
