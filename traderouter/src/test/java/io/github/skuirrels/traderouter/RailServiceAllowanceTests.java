package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.movements.LegKind;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.Location;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.RailServiceAllowance;
import io.github.skuirrels.traderouter.movements.RailServiceAllowance.TrackSegment;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.TransportMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RailServiceAllowanceTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static DefaultTradeRouterEngine engine() {
        return DefaultTradeRouterEngine.getDefault();
    }

    private static MovementRequest rail(String from, String to) {
        return MovementRequest.of("Station " + from + " to Station " + to + " Rail");
    }

    private static LegResult singleLeg(MovementResult result) {
        assertThat(result.legs()).hasSize(1);
        return result.legs().get(0);
    }

    @Test
    void defaultsKeepRailTransitTimeAsDistanceDividedBySpeed() {
        LegResult leg = singleLeg(engine().calculateMovement(rail("CAMTR", "USDET")));

        assertThat(leg.durationHours()).isCloseTo(leg.length() / 80.0, within(1e-6));
        assertThat(leg.railTerminalHours()).isEqualTo(0.0);
        assertThat(leg.railInterchangeHours()).isEqualTo(0.0);
        assertThat(leg.borderHours()).isEqualTo(0.0);
        assertThat(leg.transitHours()).isEqualTo(leg.durationHours());
        assertThat(leg.feature().properties().getBorderCrossings())
                .as("the counts are reported even when no hours are charged")
                .isEqualTo(1);
    }

    @Test
    void terminalDwellIsAddedForBothEndsOfEveryRailLeg() {
        MovementRequest request = rail("CAMTR", "USDET")
                .setRailOriginTerminalHours(24.0)
                .setRailDestinationTerminalHours(12.0);

        MovementResult result = engine().calculateMovement(request);
        LegResult leg = singleLeg(result);

        assertThat(leg.railTerminalHours()).isEqualTo(36.0);
        assertThat(leg.transitHours()).isCloseTo(leg.durationHours() + 36.0, within(1e-9));
        assertThat(result.totalRailTerminalHours()).isEqualTo(36.0);
        assertThat(result.totalTransitHours()).isCloseTo(result.totalDurationHours() + 36.0, within(1e-9));
    }

    @Test
    void borderCrossingIsChargedOnACanadaToUnitedStatesLeg() {
        MovementRequest request = rail("CAMTR", "USDET").setRailBorderCrossingHours(6.0);

        LegResult leg = singleLeg(engine().calculateMovement(request));

        assertThat(leg.feature().properties().getBorderCrossings()).isEqualTo(1);
        assertThat(leg.borderHours()).isEqualTo(6.0);
        assertThat(leg.transitHours()).isCloseTo(leg.durationHours() + 6.0, within(1e-9));
    }

    @Test
    void borderCrossingIsNotChargedWithinOneCountry() {
        MovementRequest request = rail("USLAX", "USCHI").setRailBorderCrossingHours(6.0);

        LegResult leg = singleLeg(engine().calculateMovement(request));

        assertThat(leg.feature().properties().getBorderCrossings()).isEqualTo(0);
        assertThat(leg.borderHours()).isEqualTo(0.0);
    }

    @ParameterizedTest
    @CsvSource({
            "US, CA, 1",
            "CA, US, 1",
            "MX, US, 1",
            "CA, MX, 2",
            "US, US, 0",
            "GB, US, 0",
            ", US, 0"
    })
    void countBorderCrossingsFollowsTheEndCountries(String from, String to, int expected) {
        assertThat(RailServiceAllowance.countBorderCrossings(from, to)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "USLAX, US",
            "caMTR, CA", // lower case is accepted and upper-cased
            "MX123, MX",
            "1SLAX,",    // not a country prefix
            "USLA,",     // too short for a UN/LOCODE
            "USLAXX,",
            ","          // a coordinate-only end has no code
    })
    void countryOfTakesTheCountryFromAUnLocodeShapedCode(String code, String expected) {
        ResolvedLocation location = new ResolvedLocation(code, null, new Coordinate(0, 0), null, "coordinates", null);

        assertThat(RailLegs.countryOf(location)).isEqualTo(expected);
    }

    @Test
    void countClassIInterchangesRejectsANullSegment() {
        List<TrackSegment> segments = new ArrayList<>();
        segments.add(new TrackSegment("BNSF", 1_200.0));
        segments.add(null);

        assertThatThrownBy(() -> RailServiceAllowance.countClassIInterchanges(segments))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("segments must not contain null");
    }

    @Test
    void countClassIInterchangesCountsOnlyClassIRailroadsWithALongStretchOfTheRoute() {
        List<TrackSegment> segments = List.of(
                new TrackSegment("BNSF", 1_400.0),
                new TrackSegment("UP", 600.0),   // parallel line the shortest path borrows: not counted
                new TrackSegment("BNSF", 900.0),
                new TrackSegment("NIRC", 70.0),  // commuter track, not Class I
                new TrackSegment(null, 5.0),
                new TrackSegment("NS", 1_200.0));

        assertThat(RailServiceAllowance.countClassIInterchanges(segments)).isEqualTo(1);
        assertThat(RailServiceAllowance.countClassIInterchanges(List.of(
                new TrackSegment("UP", 500.0), new TrackSegment("BNSF", 500.0)))).isEqualTo(0);
        assertThat(RailServiceAllowance.countClassIInterchanges(List.of())).isEqualTo(0);
    }

    @ParameterizedTest
    @CsvSource({
            "USLAX, USCHI, 0", // BNSF or UP single-line, though the path weaves between their lines
            "CAMTR, USDET, 0", // CN single-line
            "USLAX, USNYC, 1"  // western railroad to eastern railroad
    })
    void interchangesAreEstimatedFromTheRoutedPath(String from, String to, int expected) {
        MovementRequest request = rail(from, to).setRailInterchangeHours(24.0);

        LegResult leg = singleLeg(engine().calculateMovement(request));

        assertThat(leg.feature().properties().getRailInterchanges()).isEqualTo(expected);
        assertThat(leg.railInterchangeHours()).isEqualTo(24.0 * expected);
        assertThat(leg.transitHours()).isCloseTo(leg.durationHours() + 24.0 * expected, within(1e-9));
    }

    @Test
    void interchangeCountsOverrideTheEstimateAndApplyToGreatCircleLegs() {
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg(
                        Location.fromCoordinate(new Coordinate(-0.1278, 51.5074), "London"),
                        Location.fromCoordinate(new Coordinate(1.3108, 51.9630), "Felixstowe"),
                        TransportMode.RAIL,
                        LegKind.MAIN)))
                .setRailInterchangeHours(10.0);
        request.getRailInterchangeCounts().put(1, 2);

        LegResult leg = singleLeg(engine().calculateMovement(request));

        assertThat(leg.feature().properties().getDistanceBasis()).isEqualTo("great_circle");
        assertThat(leg.feature().properties().getRailInterchanges()).isEqualTo(2);
        assertThat(leg.railInterchangeHours()).isEqualTo(20.0);
        assertThat(leg.feature().properties().getBorderCrossings())
                .as("coordinate-only ends have no country")
                .isEqualTo(0);
    }

    @Test
    void allowancesAreReportedInTheJsonAndOnlyOnRailLegs() {
        MovementRequest request = MovementRequest.of("""
                        Port MAPTM to Port CAMTR Sea
                        Station CAMTR to Station USDET Rail
                        """)
                .setRailOriginTerminalHours(24.0)
                .setRailDestinationTerminalHours(24.0)
                .setRailBorderCrossingHours(12.0);

        MovementResult result = engine().calculateMovement(request);

        JsonNode doc = JSON.readTree(result.toJson());
        JsonNode sea = doc.get("features").get(0).get("properties");
        assertThat(sea.has("rail_terminal_hours")).isFalse();
        assertThat(sea.has("border_hours")).isFalse();
        JsonNode rail = doc.get("features").get(1).get("properties");
        assertThat(rail.get("rail_terminal_hours").asDouble()).isEqualTo(48.0);
        assertThat(rail.get("rail_interchanges").asInt()).isEqualTo(0);
        assertThat(rail.get("border_crossings").asInt()).isEqualTo(1);
        assertThat(rail.get("border_hours").asDouble()).isEqualTo(12.0);
        JsonNode totals = doc.get("properties");
        assertThat(totals.get("total_rail_terminal_hours").asDouble()).isEqualTo(48.0);
        assertThat(totals.get("total_rail_interchange_hours").asDouble()).isEqualTo(0.0);
        assertThat(totals.get("total_border_hours").asDouble()).isEqualTo(12.0);
        assertThat(result.legs().get(0).railTerminalHours()).isEqualTo(0.0);
        assertThat(result.totalTransitHours()).isCloseTo(
                result.legs().get(0).transitHours() + result.legs().get(1).durationHours() + 48.0 + 12.0,
                within(1e-9));
    }

    @Test
    void textSummaryNamesRailAllowancesOnlyWhenSet() {
        MovementResult plain = engine().calculateMovement(rail("CAMTR", "USDET"));
        MovementResult withDwell = engine().calculateMovement(rail("CAMTR", "USDET").setRailOriginTerminalHours(24.0));

        assertThat(plain.toText(false)).doesNotContain("rail terminals");
        assertThat(withDwell.toText(false)).contains("+ 24 h rail terminals");
    }

    // Java has no ArgumentOutOfRangeException or parameter name; the message names the setting instead.
    @ParameterizedTest
    @CsvSource({
            "RailOriginTerminalHours, -1.0, Rail origin terminal time",
            "RailDestinationTerminalHours, NaN, Rail destination terminal time",
            "RailInterchangeHours, Infinity, Rail interchange time",
            "RailBorderCrossingHours, -0.5, Rail border-crossing time"
    })
    void invalidAllowanceHoursAreRejected(String property, double value, String description) {
        MovementRequest request = rail("CAMTR", "USDET");
        switch (property) {
            case "RailOriginTerminalHours" -> request.setRailOriginTerminalHours(value);
            case "RailDestinationTerminalHours" -> request.setRailDestinationTerminalHours(value);
            case "RailInterchangeHours" -> request.setRailInterchangeHours(value);
            case "RailBorderCrossingHours" -> request.setRailBorderCrossingHours(value);
            default -> throw new IllegalArgumentException(property);
        }

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(description + " must be finite and non-negative. Received: " + value);
    }

    @Test
    void interchangeCountForANonRailLegIsRejected() {
        MovementRequest request = MovementRequest.of("Port MAPTM to Port CAMTR Sea").putRailInterchangeCount(1, 0);

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not refer to a rail leg");
    }

    @Test
    void interchangeCountMustNotBeNegative() {
        MovementRequest request = rail("CAMTR", "USDET").putRailInterchangeCount(1, -1);

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }
}
