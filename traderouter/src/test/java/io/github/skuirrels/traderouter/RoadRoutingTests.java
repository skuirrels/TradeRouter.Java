package io.github.skuirrels.traderouter;

import com.sun.net.httpserver.HttpServer;
import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.movements.DistanceDecayRoadDistanceEstimator;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.Location;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.OsrmRoadRouteProvider;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.RoadDistanceEstimate;
import io.github.skuirrels.traderouter.movements.RoadDistanceEstimateRequest;
import io.github.skuirrels.traderouter.movements.RoadDurationMode;
import io.github.skuirrels.traderouter.movements.RoadRouteProvider;
import io.github.skuirrels.traderouter.movements.RoadRouteRequest;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRouteStatus;
import io.github.skuirrels.traderouter.movements.RoadRoutingMode;
import io.github.skuirrels.traderouter.movements.SuppliedRoadRoute;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.ports.PortDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RoadRoutingTests {
    private static final String GATWICK_TO_HEATHROW = "Pickup GBLGW to Airport GBLHR Road";

    @Test
    void movementRoadFallbackUsesLabelledDistanceDecayEstimate() {
        LegResult road = TradeRoutes.calculateMovement(GATWICK_TO_HEATHROW).legs().get(0);

        var properties = road.feature().properties();
        assertThat(properties.getDistanceBasis()).isEqualTo("circuity_estimate");
        assertThat(properties.getDistanceSource()).isEqualTo("distance-decay-1.6174x^0.95797");
        assertThat(properties.getGeometryBasis()).isEqualTo("great_circle");
        assertThat(properties.getDistanceWarning()).contains("no road-network route");
        assertThat(properties.getStraightLineLength()).isBetween(39.0, 41.0);
        assertThat(road.length()).isCloseTo(
                DistanceDecayRoadDistanceEstimator.DEFAULT_COEFFICIENT * Math.pow(
                        properties.getStraightLineLength(), DistanceDecayRoadDistanceEstimator.DEFAULT_EXPONENT),
                within(1e-6));
        assertThat(road.durationHours()).isCloseTo(road.length() / 60.0, within(1e-6));
    }

    @Test
    void distanceDecayEstimateAppliesMoreCircuityToShorterJourneys() {
        var estimator = DistanceDecayRoadDistanceEstimator.getDefault();
        ResolvedLocation from = place("FROM", new Coordinate(0, 0));
        ResolvedLocation to = place("TO", new Coordinate(1, 1));

        double shortRatio = estimator.estimate(new RoadDistanceEstimateRequest(1, from, to, 10)).distanceKm() / 10;
        double mediumRatio = estimator.estimate(new RoadDistanceEstimateRequest(1, from, to, 100)).distanceKm() / 100;
        double longRatio = estimator.estimate(new RoadDistanceEstimateRequest(1, from, to, 1_000)).distanceKm() / 1_000;

        assertThat(shortRatio).isGreaterThan(mediumRatio);
        assertThat(mediumRatio).isGreaterThan(longRatio);
        assertThat(longRatio).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void distanceDecayEstimateCanBeOverriddenWithoutChangingTheDefault() {
        var estimator = new DistanceDecayRoadDistanceEstimator(1.4, 0.95);

        RoadDistanceEstimate estimate = estimator.estimate(new RoadDistanceEstimateRequest(
                1, place("FROM", new Coordinate(0, 0)), place("TO", new Coordinate(1, 1)), 100));

        assertThat(estimate.distanceKm()).isCloseTo(1.4 * Math.pow(100, 0.95), within(1e-9));
        assertThat(estimate.model()).isEqualTo("distance-decay-1.4x^0.95");
        assertThat(DistanceDecayRoadDistanceEstimator.getDefault().coefficient())
                .isEqualTo(DistanceDecayRoadDistanceEstimator.DEFAULT_COEFFICIENT);
    }

    @ParameterizedTest
    @CsvSource({"0.9, 0.95", "NaN, 0.95", "1.4, 0.0", "1.4, 1.1"})
    void distanceDecayEstimateRejectsInvalidParameters(double coefficient, double exponent) {
        assertThatThrownBy(() -> new DistanceDecayRoadDistanceEstimator(coefficient, exponent))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void movementSuppliedRoadRouteOverridesEstimateAndDrivesEmissions() {
        MovementRequest request = MovementRequest.of(GATWICK_TO_HEATHROW)
                .setCargoTonnes(20.0)
                .putRoadRouteOverride(1, new SuppliedRoadRoute(64.0, "known-route"));

        LegResult road = engine().calculateMovement(request).legs().get(0);

        assertThat(road.length()).isEqualTo(64.0);
        assertThat(road.durationHours()).isCloseTo(64.0 / 60.0, within(1e-9));
        assertThat(road.co2eKgPerTonne()).isCloseTo(5.888, within(1e-9));
        assertThat(road.co2eKg()).isCloseTo(117.76, within(1e-9));
        assertThat(road.feature().properties().getDistanceBasis()).isEqualTo("supplied");
        assertThat(road.feature().properties().getDistanceSource()).isEqualTo("known-route");
        assertThat(road.feature().properties().getDistanceWarning()).isNull();
    }

    @Test
    void movementAsyncUsesProviderDistanceAndConfiguredRoadSpeedByDefault() {
        var provider = new StubRoadProvider(RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm")
                .distanceKm(64.0)
                .durationHours(0.9)
                .geometry(List.of(
                        new Coordinate(-0.19028, 51.14806),
                        new Coordinate(-0.25, 51.25),
                        new Coordinate(-0.45, 51.46667)))
                .profile("driving")
                .dataVersion("planet-2026-09")
                .build());
        MovementRequest request = providerRequest(provider, RoadRoutingMode.REQUIRE_NETWORK);

        LegResult road = engine().calculateMovementAsync(request).join().legs().get(0);

        assertThat(provider.requests).hasSize(1);
        assertThat(road.length()).isEqualTo(64.0);
        assertThat(road.durationHours()).isCloseTo(64.0 / 60.0, within(1e-9));
        assertThat(road.feature().geometry().positions()).hasSize(3);
        var properties = road.feature().properties();
        assertThat(properties.getDistanceBasis()).isEqualTo("road_network");
        assertThat(properties.getDistanceSource()).isEqualTo("osrm");
        assertThat(properties.getRoutingProfile()).isEqualTo("driving");
        assertThat(properties.getRoutingDataVersion()).isEqualTo("planet-2026-09");
        assertThat(properties.getDurationBasis()).isEqualTo("assumed_speed");
        assertThat(properties.getGeometryBasis()).isEqualTo("road_network");
    }

    @Test
    void movementAsyncCanOptIntoProviderDurationWithoutConfiguredRoadSpeed() {
        var provider = new StubRoadProvider(success(64.0, 0.9));
        MovementRequest request = providerRequest(provider, RoadRoutingMode.REQUIRE_NETWORK)
                .setRoadDurationMode(RoadDurationMode.ROUTE_DURATION_WHEN_AVAILABLE);
        request.getSpeedsKmh().remove(TransportMode.ROAD);

        LegResult road = engine().calculateMovementAsync(request).join().legs().get(0);

        assertThat(road.durationHours()).isEqualTo(0.9);
        assertThat(road.feature().properties().getDurationBasis()).isEqualTo("provider");
    }

    @Test
    void movementAsyncConfiguredRoadSpeedAppliesToProviderDistance() {
        var provider = new StubRoadProvider(success(64.0, 0.9));
        MovementRequest request = providerRequest(provider, RoadRoutingMode.REQUIRE_NETWORK)
                .setSpeedKmh(TransportMode.ROAD, 80.0);

        LegResult road = engine().calculateMovementAsync(request).join().legs().get(0);

        assertThat(road.durationHours()).isCloseTo(64.0 / 80.0, within(1e-9));
        assertThat(road.feature().properties().getDurationBasis()).isEqualTo("assumed_speed");
    }

    @Test
    void movementAsyncProviderDurationOptInFallsBackToConfiguredSpeedWhenDurationIsMissing() {
        var provider = new StubRoadProvider(success(64.0, null));
        MovementRequest request = providerRequest(provider, RoadRoutingMode.REQUIRE_NETWORK)
                .setRoadDurationMode(RoadDurationMode.ROUTE_DURATION_WHEN_AVAILABLE);

        LegResult road = engine().calculateMovementAsync(request).join().legs().get(0);

        assertThat(road.durationHours()).isCloseTo(64.0 / 60.0, within(1e-9));
        assertThat(road.feature().properties().getDurationBasis()).isEqualTo("assumed_speed");
    }

    @Test
    void movementRejectsMissingRoadDurationMode() {
        MovementRequest request = MovementRequest.of(GATWICK_TO_HEATHROW).setRoadDurationMode(null);

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("road-duration mode is unknown");
    }

    @ParameterizedTest
    @EnumSource(value = RoadRouteStatus.class, names = {"OUTSIDE_COVERAGE", "UNAVAILABLE"})
    void movementAsyncProviderCoverageOrAvailabilityFailureFallsBackWithWarning(RoadRouteStatus status) {
        var provider = new StubRoadProvider(RoadRouteResult.builder(status, "osrm").message("provider detail").build());
        MovementRequest request = providerRequest(provider, RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE);

        LegResult road = engine().calculateMovementAsync(request).join().legs().get(0);

        assertThat(road.feature().properties().getDistanceBasis()).isEqualTo("circuity_estimate");
        assertThat(road.feature().properties().getDistanceWarning())
                .startsWith("osrm returned " + status.displayName() + ": provider detail.");
    }

    @Test
    void movementAsyncNoRouteDoesNotSilentlyEstimate() {
        var provider = new StubRoadProvider(
                RoadRouteResult.builder(RoadRouteStatus.NO_ROUTE, "osrm").message("disconnected network").build());
        MovementRequest request = providerRequest(provider, RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE);

        assertThatThrownBy(() -> engine().calculateMovementAsync(request).join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no route")
                .hasMessageContaining("disconnected network");
    }

    @Test
    void movementSyncRejectsProviderIo() {
        MovementRequest request = providerRequest(
                new StubRoadProvider(RoadRouteResult.builder(RoadRouteStatus.UNAVAILABLE, "stub").build()),
                RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE);

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("calculateMovementAsync");
    }

    @Test
    void movementSyncSuppliedRouteTakesPrecedenceWithoutCallingConfiguredProvider() {
        var provider = new StubRoadProvider(success(70.0, null));
        MovementRequest request = providerRequest(provider, RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE)
                .putRoadRouteOverride(1, new SuppliedRoadRoute(64.0, "known-route"));

        LegResult road = engine().calculateMovement(request).legs().get(0);

        assertThat(provider.requests).isEmpty();
        assertThat(road.length()).isEqualTo(64.0);
        assertThat(road.feature().properties().getDistanceBasis()).isEqualTo("supplied");
    }

    @Test
    void movementAsyncRequireNetworkRejectsMissingProvider() {
        MovementRequest request = MovementRequest.of(GATWICK_TO_HEATHROW)
                .setRoadRoutingMode(RoadRoutingMode.REQUIRE_NETWORK);

        assertThatThrownBy(() -> engine().calculateMovementAsync(request).join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a road-network provider");
    }

    @Test
    void movementSyncEstimateOnlyDoesNotCallConfiguredProvider() {
        var provider = new StubRoadProvider(success(64.0, null));
        MovementRequest request = providerRequest(provider, RoadRoutingMode.ESTIMATE_ONLY);

        LegResult road = engine().calculateMovement(request).legs().get(0);

        assertThat(provider.requests).isEmpty();
        assertThat(road.feature().properties().getDistanceBasis()).isEqualTo("circuity_estimate");
    }

    @Test
    void movementAsyncCancellationCancelsTheInFlightProviderCall() {
        CompletableFuture<RoadRouteResult> pending = new CompletableFuture<>();
        RoadRouteProvider provider = request -> pending;
        MovementRequest request = providerRequest(provider, RoadRoutingMode.REQUIRE_NETWORK);

        CompletableFuture<?> movement = engine().calculateMovementAsync(request);
        movement.cancel(true);

        assertThat(movement).isCancelled();
        assertThat(pending).isCancelled();
    }

    @Test
    void movementAsyncProviderGeometryAlwaysRetainsExactMovementEndpoints() {
        var provider = new StubRoadProvider(RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "stub")
                .distanceKm(64.0)
                .durationHours(1.0)
                .geometry(List.of(
                        new Coordinate(-0.190279, 51.14806),
                        new Coordinate(-0.25, 51.25),
                        new Coordinate(-0.450001, 51.46667)))
                .build());
        MovementRequest request = providerRequest(provider, RoadRoutingMode.REQUIRE_NETWORK);

        LegResult road = engine().calculateMovementAsync(request).join().legs().get(0);

        List<double[]> positions = road.feature().geometry().positions();
        assertThat(positions).hasSize(5);
        assertThat(positions.get(0)[0]).isCloseTo(-0.19028, within(1e-12));
        assertThat(positions.get(0)[1]).isEqualTo(51.14806);
        assertThat(positions.get(4)[0]).isCloseTo(-0.45, within(1e-12));
        assertThat(positions.get(4)[1]).isEqualTo(51.46667);
    }

    @Test
    void movementAsyncZeroLengthProviderGeometryRemainsAValidLineString() {
        Coordinate coordinate = new Coordinate(-0.45, 51.46667);
        var provider = new StubRoadProvider(RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "stub")
                .distanceKm(0.0)
                .durationHours(0.0)
                .geometry(List.of(coordinate, coordinate, coordinate))
                .build());
        MovementRequest request = new MovementRequest(List.of(new MovementLeg(
                        Location.fromCoordinate(coordinate), Location.fromCoordinate(coordinate), TransportMode.ROAD)))
                .setRoadRouteProvider(provider)
                .setRoadRoutingMode(RoadRoutingMode.REQUIRE_NETWORK);

        LegResult road = engine().calculateMovementAsync(request).join().legs().get(0);

        assertThat(road.feature().geometry().type()).isEqualTo("LineString");
        assertThat(road.feature().geometry().positions()).hasSize(2);
        assertThat(road.feature().geometry().positions().get(0)).containsExactly(road.feature().geometry().positions().get(1));
    }

    @Test
    void movementAsyncRejectsBlankProviderSourceForFailureResult() {
        MovementRequest request = providerRequest(
                new StubRoadProvider(RoadRouteResult.builder(RoadRouteStatus.UNAVAILABLE, " ").build()),
                RoadRoutingMode.PREFER_NETWORK_THEN_ESTIMATE);

        assertThatThrownBy(() -> engine().calculateMovementAsync(request).join())
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank source");
    }

    @Test
    void movementAsyncRejectsZeroProviderDurationForNonZeroDistance() {
        MovementRequest request = providerRequest(new StubRoadProvider(success(64.0, 0.0)), RoadRoutingMode.REQUIRE_NETWORK);

        assertThatThrownBy(() -> engine().calculateMovementAsync(request).join())
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-positive duration");
    }

    @Test
    void osrmProviderParsesDistanceDurationGeometryAndBuildsInvariantRequest() throws IOException {
        String json = """
                {
                  "code": "Ok",
                  "routes": [{
                    "distance": 64321.0,
                    "duration": 3600.0,
                    "geometry": {
                      "type": "LineString",
                      "coordinates": [[-0.19028, 51.14806], [-0.45, 51.46667]]
                    }
                  }]
                }
                """;
        try (StubOsrm osrm = new StubOsrm(200, json)) {
            var provider = new OsrmRoadRouteProvider(
                    HttpClient.newHttpClient(), osrm.uri(), OsrmRoadRouteProvider.DEFAULT_PROFILE, "planet-test",
                    OsrmRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, null);
            var request = new RoadRouteRequest(
                    1,
                    new ResolvedLocation("GBLGW", "Gatwick", new Coordinate(-0.19028, 51.14806), null, "test"),
                    new ResolvedLocation("GBLHR", "Heathrow", new Coordinate(-0.45, 51.46667), null, "test"));

            RoadRouteResult result = provider.routeAsync(request).join();

            assertThat(result.status()).isEqualTo(RoadRouteStatus.SUCCESS);
            assertThat(result.distanceKm()).isEqualTo(64.321);
            assertThat(result.durationHours()).isEqualTo(1.0);
            assertThat(result.geometry()).hasSize(2);
            assertThat(result.dataVersion()).isEqualTo("planet-test");
            assertThat(osrm.requestUri.get()).contains(
                    "/route/v1/driving/-0.19028,51.14806;-0.45,51.46667?overview=full&geometries=geojson&steps=false&radiuses=10000;10000");
        }
    }

    @Test
    void osrmProviderMapsNoSegmentToOutsideCoverage() throws IOException {
        try (StubOsrm osrm = new StubOsrm(400, "{\"code\":\"NoSegment\",\"message\":\"Could not find a matching segment\"}")) {
            var provider = new OsrmRoadRouteProvider(HttpClient.newHttpClient(), osrm.uri());

            RoadRouteResult result = provider.routeAsync(directProviderRequest()).join();

            assertThat(result.status()).isEqualTo(RoadRouteStatus.OUTSIDE_COVERAGE);
            assertThat(result.message()).contains("matching segment");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":\"Ok\",\"routes\":[{\"duration\":120}]}",
            "{\"code\":\"Ok\",\"routes\":[{\"distance\":1000}]}"})
    void osrmProviderMissingRouteSummaryValueIsUnavailable(String json) throws IOException {
        try (StubOsrm osrm = new StubOsrm(200, json)) {
            var provider = new OsrmRoadRouteProvider(HttpClient.newHttpClient(), osrm.uri());

            RoadRouteResult result = provider.routeAsync(directProviderRequest()).join();

            assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
            assertThat(result.message()).contains("no usable route summary");
        }
    }

    @Test
    void osrmProviderReportsDeeplyNestedJsonAsUnavailable() throws IOException {
        try (StubOsrm osrm = new StubOsrm(200, "[".repeat(200_000))) {
            var provider = new OsrmRoadRouteProvider(HttpClient.newHttpClient(), osrm.uri());

            RoadRouteResult result = provider.routeAsync(directProviderRequest()).join();

            assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
            assertThat(result.message()).startsWith("OSRM returned invalid JSON").contains("maximum depth");
        }
    }

    @Test
    void osrmProviderNormalizesLongitudesForTheHttpApi() throws IOException {
        try (StubOsrm osrm = new StubOsrm(400, "{\"code\":\"NoSegment\"}")) {
            var provider = new OsrmRoadRouteProvider(HttpClient.newHttpClient(), osrm.uri());
            var request = new RoadRouteRequest(
                    1,
                    new ResolvedLocation(null, null, new Coordinate(190, 1), null, "test"),
                    new ResolvedLocation(null, null, new Coordinate(-190, 2), null, "test"));

            provider.routeAsync(request).join();

            assertThat(osrm.requestUri.get()).contains("/-170,1;170,2");
        }
    }

    @Test
    void osrmProviderReportsAnUnreachableServiceAsUnavailable() throws IOException {
        URI closed;
        try (StubOsrm osrm = new StubOsrm(200, "{}")) {
            closed = osrm.uri();
        }
        var provider = new OsrmRoadRouteProvider(HttpClient.newHttpClient(), closed);

        RoadRouteResult result = provider.routeAsync(directProviderRequest()).join();

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).startsWith("OSRM was unavailable");
    }

    @Test
    void osrmProviderRejectsNonHttpBaseUri() {
        assertThatThrownBy(() -> new OsrmRoadRouteProvider(HttpClient.newHttpClient(), URI.create("file:///tmp/osrm")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP or HTTPS");
    }

    @Test
    void seaRouteReportsNetworkDistanceProvenance() {
        GeoJsonFeature route = TradeRoutes.calculate("FRMRS", "ZACPT");

        assertThat(route.properties().getDistanceBasis()).isEqualTo("maritime_network");
        assertThat(route.properties().getDistanceSource()).isEqualTo("marnet");
        assertThat(route.properties().getDurationBasis()).isEqualTo("assumed_speed");
        assertThat(route.properties().getGeometryBasis()).isEqualTo("maritime_network");
    }

    @Test
    void seaRouteCustomGraphDoesNotClaimMarnetProvenance() {
        MaritimeGraph graph = new MaritimeGraph();
        graph.addEdge(new Coordinate(0, 0), new Coordinate(1, 0));
        graph.buildIndex();
        var engine = new DefaultTradeRouterEngine(graph, new PortDatabase(List.of()));

        GeoJsonFeature route = engine.calculateRoute(new Coordinate(0, 0), new Coordinate(1, 0));

        assertThat(route.properties().getDistanceBasis()).isEqualTo("maritime_network");
        assertThat(route.properties().getDistanceSource()).isEqualTo("custom_graph");
    }

    @Test
    void movementJsonCarriesDistanceProvenance() {
        String json = TradeRoutes.calculateMovement(GATWICK_TO_HEATHROW).toJson();

        assertThat(json).contains("\"distance_basis\":\"circuity_estimate\"");
        assertThat(json).contains("\"straight_line_length\":");
        assertThat(json).contains("\"distance_warning\":");
    }

    private static DefaultTradeRouterEngine engine() {
        return DefaultTradeRouterEngine.getDefault();
    }

    private static ResolvedLocation place(String code, Coordinate coordinate) {
        return new ResolvedLocation(code, code, coordinate, null, "test", LocationFunction.of(LocationFunction.ROAD_TERMINAL));
    }

    private static RoadRouteResult success(double distanceKm, Double durationHours) {
        return RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "stub")
                .distanceKm(distanceKm)
                .durationHours(durationHours)
                .build();
    }

    private static RoadRouteRequest directProviderRequest() {
        return new RoadRouteRequest(
                1,
                new ResolvedLocation(null, null, new Coordinate(0, 0), null, "test"),
                new ResolvedLocation(null, null, new Coordinate(1, 1), null, "test"));
    }

    private static MovementRequest providerRequest(RoadRouteProvider provider, RoadRoutingMode mode) {
        return new MovementRequest(MovementParser.parse(GATWICK_TO_HEATHROW))
                .setRoadRouteProvider(provider)
                .setRoadRoutingMode(mode);
    }

    private static final class StubRoadProvider implements RoadRouteProvider {
        private final RoadRouteResult result;
        private final List<RoadRouteRequest> requests = new ArrayList<>();

        StubRoadProvider(RoadRouteResult result) {
            this.result = result;
        }

        @Override
        public CompletableFuture<RoadRouteResult> routeAsync(RoadRouteRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(result);
        }
    }

    /** A loopback HTTP server that answers every request with one canned OSRM response. */
    private static final class StubOsrm implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> requestUri = new AtomicReference<>();

        StubOsrm(int status, String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requestUri.set(exchange.getRequestURI().toString());
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
