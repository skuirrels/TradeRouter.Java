package io.github.skuirrels.traderouter;

import com.sun.net.httpserver.HttpServer;
import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.movements.GoogleRoutesRoadRouteProvider;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.Location;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.RoadRouteRequest;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRouteStatus;
import io.github.skuirrels.traderouter.movements.RoadRoutingMode;
import io.github.skuirrels.traderouter.movements.TransportMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleRoutesRoadRouteProviderTests {
    private static final String API_KEY = "test-key-not-real";
    private static final String LINE = "{\"type\":\"LineString\",\"coordinates\":[[0,0],[0.5,0.5],[1,1]]}";

    @Test
    void routeAsyncSuccessMapsDistanceDurationAndGeometry() throws IOException {
        RoadRouteResult result = route(
                "{\"routes\":[{\"distanceMeters\":157000,\"duration\":\"7200s\",\"polyline\":{\"geoJsonLinestring\":" + LINE + "}}]}", 200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.SUCCESS);
        assertThat(result.distanceKm()).isEqualTo(157.0);
        assertThat(result.durationHours()).isEqualTo(2.0);
        assertThat(result.geometry()).containsExactly(new Coordinate(0, 0), new Coordinate(0.5, 0.5), new Coordinate(1, 1));
        assertThat(result.source()).isEqualTo("google-routes");
        assertThat(result.profile()).isEqualTo("DRIVE");
        assertThat(result.dataVersion()).isNull();
    }

    @Test
    void routeAsyncSendsKeyFieldMaskAndRequestBody() throws IOException {
        try (StubGoogle google = StubGoogle.responding(200, "{}")) {
            var provider = new GoogleRoutesRoadRouteProvider(
                    HttpClient.newHttpClient(), " " + API_KEY + " ", " drive ",
                    GoogleRoutesRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, google.uri(), null);

            provider.routeAsync(createRequest(new Coordinate(181, 10), new Coordinate(1, 1))).join();

            assertThat(google.method.get()).isEqualTo("POST");
            assertThat(google.apiKey.get()).isEqualTo(API_KEY);
            assertThat(google.fieldMask.get()).isEqualTo("routes.distanceMeters,routes.duration,routes.polyline.geoJsonLinestring");

            JsonNode root = new ObjectMapper().readTree(google.body.get());
            assertThat(root.path("travelMode").asString()).isEqualTo("DRIVE");
            assertThat(root.path("polylineEncoding").asString()).isEqualTo("GEO_JSON_LINESTRING");
            assertThat(root.path("polylineQuality").asString()).isEqualTo("HIGH_QUALITY");
            // Traffic-aware routing is billed at the Pro SKU.
            assertThat(root.has("routingPreference")).isFalse();
            JsonNode origin = root.path("origin").path("location").path("latLng");
            assertThat(origin.path("latitude").asDouble()).isEqualTo(10);
            assertThat(origin.path("longitude").asDouble()).isEqualTo(-179);
            assertThat(root.path("destination").path("location").path("latLng").path("longitude").asDouble()).isEqualTo(1);
        }
    }

    @Test
    void routeAsyncPostsToTheDefaultComputeRoutesUri() {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var provider = new GoogleRoutesRoadRouteProvider(new StubHttpClient(request -> {
            sent.set(request);
            return CompletableFuture.failedFuture(new ConnectException("not sent"));
        }), API_KEY);

        provider.routeAsync(createRequest()).join();

        assertThat(sent.get().method()).isEqualTo("POST");
        assertThat(sent.get().uri()).isEqualTo(URI.create("https://routes.googleapis.com/directions/v2:computeRoutes"));
        assertThat(sent.get().headers().allValues("X-Goog-Api-Key")).containsExactly(API_KEY);
    }

    @Test
    void routeAsyncBuildsUriFromCustomBasePath() throws IOException {
        try (StubGoogle google = StubGoogle.responding(200, "{}")) {
            var provider = new GoogleRoutesRoadRouteProvider(
                    HttpClient.newHttpClient(), API_KEY, GoogleRoutesRoadRouteProvider.DEFAULT_TRAVEL_MODE,
                    GoogleRoutesRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, URI.create(google.uri() + "/google"), null);

            provider.routeAsync(createRequest()).join();

            assertThat(google.requestUri.get().getPath()).isEqualTo("/google/directions/v2:computeRoutes");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"routes\":[]}"})
    void routeAsyncNoRoutesIsNoRoute(String json) throws IOException {
        RoadRouteResult result = route(json, 200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.NO_ROUTE);
        assertThat(result.message()).isEqualTo("Google Routes found no route between the endpoints.");
    }

    @Test
    void routeAsyncZeroLengthRouteWithOmittedFieldsSucceeds() throws IOException {
        try (StubGoogle google = StubGoogle.responding(
                200, "{\"routes\":[{\"polyline\":{\"geoJsonLinestring\":{\"type\":\"LineString\",\"coordinates\":[[0,0],[0,0]]}}}]}")) {
            RoadRouteResult result = provider(google).routeAsync(createRequest(new Coordinate(0, 0), new Coordinate(0, 0))).join();

            assertThat(result.status()).isEqualTo(RoadRouteStatus.SUCCESS);
            assertThat(result.distanceKm()).isEqualTo(0.0);
            assertThat(result.durationHours()).isEqualTo(0.0);
        }
    }

    static Stream<Arguments> httpErrors() {
        return Stream.of(
                Arguments.of(403, "{\"error\":{\"code\":403,\"message\":\"Routes API has not been used in project 1.\",\"status\":\"PERMISSION_DENIED\"}}",
                        "Google Routes returned HTTP 403 PERMISSION_DENIED: Routes API has not been used in project 1."),
                Arguments.of(429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}", "Google Routes returned HTTP 429 RESOURCE_EXHAUSTED"),
                Arguments.of(502, "<html>bad gateway</html>", "Google Routes returned HTTP 502."),
                Arguments.of(500, "{}", "Google Routes returned HTTP 500."));
    }

    @ParameterizedTest
    @MethodSource("httpErrors")
    void routeAsyncHttpErrorIsUnavailableWithoutLeakingKey(int status, String body, String expectedMessage) throws IOException {
        RoadRouteResult result = route(body, status);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).isEqualTo(expectedMessage);
        assertThat(result.message()).doesNotContain(API_KEY);
    }

    @Test
    void routeAsyncConnectionFailureIsUnavailable() {
        var provider = new GoogleRoutesRoadRouteProvider(
                new StubHttpClient(request -> CompletableFuture.failedFuture(new ConnectException("connection refused"))), API_KEY);

        RoadRouteResult result = provider.routeAsync(createRequest()).join();

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("Google Routes was unavailable: connection refused");
    }

    @Test
    void routeAsyncHttpClientTimeoutIsUnavailable() throws IOException {
        // The .NET test sets HttpClient.Timeout; the JDK client takes the timeout per request via the constructor.
        try (StubGoogle google = StubGoogle.hanging()) {
            var provider = new GoogleRoutesRoadRouteProvider(
                    HttpClient.newHttpClient(), API_KEY, GoogleRoutesRoadRouteProvider.DEFAULT_TRAVEL_MODE,
                    GoogleRoutesRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, google.uri(), Duration.ofMillis(100));

            RoadRouteResult result = provider.routeAsync(createRequest()).join();

            assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
            assertThat(result.message()).isEqualTo("The Google Routes request timed out.");
        }
    }

    @Test
    void routeAsyncCallerCancellationPropagates() throws IOException {
        try (StubGoogle google = StubGoogle.hanging()) {
            CompletableFuture<RoadRouteResult> pending = provider(google).routeAsync(createRequest());
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> pending.cancel(true));

            assertThatThrownBy(pending::join).satisfiesAnyOf(
                    thrown -> assertThat(thrown).isInstanceOf(CancellationException.class),
                    thrown -> assertThat(thrown).isInstanceOf(CompletionException.class)
                            .hasCauseInstanceOf(CancellationException.class));
        }
    }

    @Test
    void routeAsyncInvalidJsonIsUnavailable() throws IOException {
        RoadRouteResult result = route("this is not json", 200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).startsWith("Google Routes returned invalid JSON:");
    }

    @Test
    void routeAsyncNullJsonBodyIsUnavailable() throws IOException {
        RoadRouteResult result = route("null", 200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("Google Routes returned an empty response.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"distanceMeters\":-1,\"duration\":\"60s\"",
            "\"distanceMeters\":1000,\"duration\":\"-1s\"",
            "\"distanceMeters\":1000,\"duration\":\"60\"",
            "\"distanceMeters\":1000,\"duration\":\"0s\"",
            "\"distanceMeters\":1000"})
    void routeAsyncUnusableRouteSummaryIsUnavailable(String summary) throws IOException {
        RoadRouteResult result = route("{\"routes\":[{" + summary + ",\"polyline\":{\"geoJsonLinestring\":" + LINE + "}}]}", 200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("Google Routes returned no usable route summary.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"distanceMeters\":1000,\"duration\":\"60s\"}",
            "{\"distanceMeters\":1000,\"duration\":\"60s\",\"polyline\":{\"geoJsonLinestring\":{\"type\":\"LineString\",\"coordinates\":[[0,0]]}}}",
            "{\"distanceMeters\":1000,\"duration\":\"60s\",\"polyline\":{\"geoJsonLinestring\":{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,95]]}}}"})
    void routeAsyncMissingOrUnusableGeometryIsUnavailable(String route) throws IOException {
        RoadRouteResult result = route("{\"routes\":[" + route + "]}", 200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("Google Routes returned no usable route geometry.");
    }

    @Test
    void routeAsyncEndpointSnappedBeyondLimitIsOutsideCoverage() throws IOException {
        try (StubGoogle google = StubGoogle.responding(
                200, "{\"routes\":[{\"distanceMeters\":1000,\"duration\":\"60s\",\"polyline\":{\"geoJsonLinestring\":"
                        + "{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1.1]]}}}]}")) {
            var provider = new GoogleRoutesRoadRouteProvider(
                    HttpClient.newHttpClient(), API_KEY, GoogleRoutesRoadRouteProvider.DEFAULT_TRAVEL_MODE, 5_000, google.uri(), null);

            RoadRouteResult result = provider.routeAsync(createRequest()).join();

            assertThat(result.status()).isEqualTo(RoadRouteStatus.OUTSIDE_COVERAGE);
            assertThat(result.message()).startsWith("Google Routes snapped an endpoint 11").endsWith("beyond the 5000 m limit.");
        }
    }

    @Test
    void routeAsyncRejectsSequenceBelowOne() {
        var provider = new GoogleRoutesRoadRouteProvider(
                new StubHttpClient(request -> CompletableFuture.failedFuture(new IOException("not expected"))), API_KEY);
        var request = new RoadRouteRequest(0, createRequest().from(), createRequest().to());

        assertThatThrownBy(() -> provider.routeAsync(request).join()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routeAsyncRejectsInvalidEndpointBeforeCallingGoogle() {
        AtomicBoolean called = new AtomicBoolean();
        var provider = new GoogleRoutesRoadRouteProvider(new StubHttpClient(request -> {
            called.set(true);
            return CompletableFuture.failedFuture(new IOException("not expected"));
        }), API_KEY);

        assertThatThrownBy(() -> provider.routeAsync(createRequest(new Coordinate(0, 91), new Coordinate(1, 1))).join())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
        assertThat(called).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void constructorRejectsBlankApiKeyOrTravelMode(String value) {
        HttpClient client = HttpClient.newHttpClient();

        assertThatThrownBy(() -> new GoogleRoutesRoadRouteProvider(client, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API key cannot be blank");
        assertThatThrownBy(() -> new GoogleRoutesRoadRouteProvider(
                client, API_KEY, value, GoogleRoutesRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("travel mode cannot be blank");
    }

    static Stream<Arguments> unusableBaseUris() {
        return Stream.of(
                Arguments.of("routes/v2", "must be absolute"),
                Arguments.of("ftp://routes.example/", "HTTP or HTTPS"),
                Arguments.of("https://routes.example/?key=1", "query string or fragment"));
    }

    @ParameterizedTest
    @MethodSource("unusableBaseUris")
    void constructorRejectsUnusableBaseUri(String uri, String expectedMessage) {
        HttpClient client = HttpClient.newHttpClient();

        assertThatThrownBy(() -> new GoogleRoutesRoadRouteProvider(
                client, API_KEY, GoogleRoutesRoadRouteProvider.DEFAULT_TRAVEL_MODE,
                GoogleRoutesRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, URI.create(uri), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void constructorRejectsInvalidSnapDistance(double snapDistance) {
        HttpClient client = HttpClient.newHttpClient();

        assertThatThrownBy(() -> new GoogleRoutesRoadRouteProvider(
                client, API_KEY, GoogleRoutesRoadRouteProvider.DEFAULT_TRAVEL_MODE, snapDistance, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snap distance");
    }

    @Test
    void constructorRejectsNullArguments() {
        HttpClient client = HttpClient.newHttpClient();

        assertThatThrownBy(() -> new GoogleRoutesRoadRouteProvider(null, API_KEY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GoogleRoutesRoadRouteProvider(client, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void calculateMovementAsyncUsesGoogleRouteForRoadLeg() throws IOException {
        try (StubGoogle google = StubGoogle.responding(
                200, "{\"routes\":[{\"distanceMeters\":157000,\"duration\":\"7200s\",\"polyline\":{\"geoJsonLinestring\":" + LINE + "}}]}")) {
            MovementRequest request = new MovementRequest(List.of(new MovementLeg(
                            Location.fromCoordinate(new Coordinate(0, 0)), Location.fromCoordinate(new Coordinate(1, 1)), TransportMode.ROAD)))
                    .setRoadRouteProvider(provider(google))
                    .setRoadRoutingMode(RoadRoutingMode.REQUIRE_NETWORK);

            LegResult road = TradeRoutes.calculateMovementAsync(request).join().legs().get(0);

            assertThat(road.length()).isEqualTo(157.0);
            assertThat(road.feature().properties().getDistanceSource()).isEqualTo("google-routes");
            assertThat(road.feature().properties().getDistanceBasis()).isEqualTo("road_network");
        }
    }

    private static RoadRouteRequest createRequest() {
        return createRequest(new Coordinate(0, 0), new Coordinate(1, 1));
    }

    private static RoadRouteRequest createRequest(Coordinate from, Coordinate to) {
        return new RoadRouteRequest(
                1,
                new ResolvedLocation(null, null, from, null, "test"),
                new ResolvedLocation(null, null, to, null, "test"));
    }

    private static GoogleRoutesRoadRouteProvider provider(StubGoogle google) {
        return new GoogleRoutesRoadRouteProvider(
                HttpClient.newHttpClient(), API_KEY, GoogleRoutesRoadRouteProvider.DEFAULT_TRAVEL_MODE,
                GoogleRoutesRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, google.uri(), null);
    }

    /** Routes the default request through a loopback server returning one canned Routes API response. */
    private static RoadRouteResult route(String json, int status) throws IOException {
        try (StubGoogle google = StubGoogle.responding(status, json)) {
            return provider(google).routeAsync(createRequest()).join();
        }
    }

    /** A loopback HTTP server that records the request and answers with one canned response, or never answers. */
    private static final class StubGoogle implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-google");
            thread.setDaemon(true);
            return thread;
        });
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<URI> requestUri = new AtomicReference<>();
        private final AtomicReference<String> apiKey = new AtomicReference<>();
        private final AtomicReference<String> fieldMask = new AtomicReference<>();
        private final AtomicReference<String> body = new AtomicReference<>();

        private StubGoogle(int status, String response, boolean hang) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                method.set(exchange.getRequestMethod());
                requestUri.set(exchange.getRequestURI());
                apiKey.set(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"));
                fieldMask.set(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (hang) {
                    try {
                        released.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                    return;
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        static StubGoogle responding(int status, String response) throws IOException {
            return new StubGoogle(status, response, false);
        }

        static StubGoogle hanging() throws IOException {
            return new StubGoogle(0, "", true);
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            released.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
