package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.movements.CircuityRoadDistanceEstimator;
import io.github.skuirrels.traderouter.movements.DistanceDecayRoadDistanceEstimator;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.RoadDistanceEstimate;
import io.github.skuirrels.traderouter.movements.RoadDistanceEstimateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RoadDistanceEstimatorTests {
    private static final ResolvedLocation FROM = new ResolvedLocation(
            "FROM", "From", new Coordinate(0, 0), null, "test", LocationFunction.of(LocationFunction.ROAD_TERMINAL));
    private static final ResolvedLocation TO = new ResolvedLocation(
            "TO", "To", new Coordinate(1, 1), null, "test", LocationFunction.of(LocationFunction.ROAD_TERMINAL));

    @Test
    void circuityEstimatorDefaultAppliesThirtyPercentCircuity() {
        RoadDistanceEstimate estimate = CircuityRoadDistanceEstimator.getDefault()
                .estimate(new RoadDistanceEstimateRequest(1, FROM, TO, 100));

        assertThat(CircuityRoadDistanceEstimator.getDefault().factor()).isEqualTo(CircuityRoadDistanceEstimator.DEFAULT_FACTOR);
        assertThat(estimate.distanceKm()).isCloseTo(130.0, within(1e-9));
        assertThat(estimate.model()).isEqualTo("circuity-1.3");
        assertThat(estimate.warning()).contains("great-circle distance");
    }

    @ParameterizedTest
    @CsvSource({"1.0, 100.0, circuity-1", "1.25, 125.0, circuity-1.25", "1.4567, 145.67, circuity-1.457"})
    void circuityEstimatorUsesSuppliedFactorAndNamesIt(double factor, double expectedKm, String expectedModel) {
        RoadDistanceEstimate estimate = new CircuityRoadDistanceEstimator(factor)
                .estimate(new RoadDistanceEstimateRequest(1, FROM, TO, 100));

        assertThat(estimate.distanceKm()).isCloseTo(expectedKm, within(1e-9));
        assertThat(estimate.model()).isEqualTo(expectedModel);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.99, 0.0, -1.3, Double.NaN, Double.POSITIVE_INFINITY})
    void circuityEstimatorRejectsFactorBelowOneOrNotFinite(double factor) {
        assertThatThrownBy(() -> new CircuityRoadDistanceEstimator(factor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("circuity factor must be finite and at least 1");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void estimatorsRejectInvalidStraightLineDistance(double straightLineKm) {
        var request = new RoadDistanceEstimateRequest(1, FROM, TO, straightLineKm);

        assertThatThrownBy(() -> CircuityRoadDistanceEstimator.getDefault().estimate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Straight-line distance");
        assertThatThrownBy(() -> DistanceDecayRoadDistanceEstimator.getDefault().estimate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Straight-line distance");
    }

    @Test
    void estimatorsRejectNullRequest() {
        assertThatThrownBy(() -> CircuityRoadDistanceEstimator.getDefault().estimate(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DistanceDecayRoadDistanceEstimator.getDefault().estimate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void estimatorsReturnZeroForZeroStraightLineDistance() {
        var request = new RoadDistanceEstimateRequest(1, FROM, FROM, 0.0);

        assertThat(CircuityRoadDistanceEstimator.getDefault().estimate(request).distanceKm()).isEqualTo(0.0);
        assertThat(DistanceDecayRoadDistanceEstimator.getDefault().estimate(request).distanceKm()).isEqualTo(0.0);
    }

    @Test
    void movementUsesConfiguredCircuityEstimatorForRoadLegs() {
        var request = new MovementRequest(MovementParser.parse("Pickup GBLGW to Airport GBLHR Road"))
                .setRoadDistanceEstimator(new CircuityRoadDistanceEstimator(1.25));

        List<LegResult> legs = DefaultTradeRouterEngine.getDefault().calculateMovement(request).legs();
        assertThat(legs).hasSize(1);
        LegResult road = legs.get(0);

        var properties = road.feature().properties();
        assertThat(road.length()).isCloseTo(properties.getStraightLineLength() * 1.25, within(1e-9));
        assertThat(properties.getDistanceBasis()).isEqualTo("circuity_estimate");
        assertThat(properties.getDistanceSource()).isEqualTo("circuity-1.25");
        assertThat(properties.getDistanceWarning()).contains("great-circle distance");
        assertThat(road.durationHours()).isCloseTo(road.length() / 60.0, within(1e-9));
    }
}
