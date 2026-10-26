package io.github.skuirrels.traderouter;

import com.sun.net.httpserver.HttpServer;
import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.movements.OsrmRoadRouteProvider;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.RoadRouteRequest;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRouteStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsrmRoadRouteProviderTests {
    private static final URI BASE_URI = URI.create("http://127.0.0.1:5000");

    @Test
    void routeAsyncConnectionFailureIsUnavailable() {
        var provider = createProvider(request -> CompletableFuture.failedFuture(new ConnectException("connection refused")));

        RoadRouteResult result = provider.routeAsync(createRequest()).join();

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.source()).isEqualTo("osrm");
        assertThat(result.message()).isEqualTo("OSRM was unavailable: connection refused");
    }

    @Test
    void routeAsyncHttpClientTimeoutIsUnavailable() throws IOException {
        // The .NET test sets HttpClient.Timeout; the JDK client takes the timeout per request via the constructor.
        try (StubOsrm osrm = StubOsrm.hanging()) {
            var provider = new OsrmRoadRouteProvider(
                    HttpClient.newHttpClient(), osrm.uri(), OsrmRoadRouteProvider.DEFAULT_PROFILE, null,
                    OsrmRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, Duration.ofMillis(100));

            RoadRouteResult result = provider.routeAsync(createRequest()).join();

            assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
            assertThat(result.message()).isEqualTo("The OSRM request timed out.");
        }
    }

    @Test
    void routeAsyncCallerCancellationPropagates() throws IOException {
        // The caller cancels by cancelling the returned future, the Java counterpart of a CancellationToken.
        try (StubOsrm osrm = StubOsrm.hanging()) {
            var provider = new OsrmRoadRouteProvider(HttpClient.newHttpClient(), osrm.uri());

            CompletableFuture<RoadRouteResult> pending = provider.routeAsync(createRequest());
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> pending.cancel(true));

            // .NET await unwraps the cancellation; join() reports it directly when the future itself is cancelled,
            // or wrapped in a CompletionException when the HTTP client's cancellation is rethrown by the provider.
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
        assertThat(result.message()).startsWith("OSRM returned invalid JSON:");
    }

    @Test
    void routeAsyncNullJsonBodyIsUnavailable() throws IOException {
        RoadRouteResult result = route("null", 200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("OSRM returned an empty response.");
    }

    static Stream<Arguments> noRouteResponses() {
        return Stream.of(
                Arguments.of("{\"code\":\"NoRoute\",\"message\":\"Impossible route between points\"}", "Impossible route between points"),
                Arguments.of("{\"code\":\"NoRoute\"}", "OSRM found no traversable route between the endpoints."));
    }

    @ParameterizedTest
    @MethodSource("noRouteResponses")
    void routeAsyncNoRouteIsReportedAsNoRoute(String json, String expectedMessage) throws IOException {
        RoadRouteResult result = route(json, 400);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.NO_ROUTE);
        assertThat(result.message()).isEqualTo(expectedMessage);
    }

    @Test
    void routeAsyncNoSegmentWithoutMessageUsesDefaultReason() throws IOException {
        RoadRouteResult result = route("{\"code\":\"NoSegment\"}", 400);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.OUTSIDE_COVERAGE);
        assertThat(result.message()).isEqualTo("OSRM could not match an endpoint to its loaded road network.");
    }

    static Stream<Arguments> errorResponses() {
        return Stream.of(
                Arguments.of("{\"code\":\"Error\"}", 500, "OSRM returned HTTP 500 with code 'Error'."),
                Arguments.of("{}", 503, "OSRM returned HTTP 503 with code 'unknown'."),
                Arguments.of("{\"code\":\"InvalidQuery\",\"message\":\"Query string malformed\"}", 200, "Query string malformed"),
                Arguments.of("{\"code\":\"Ok\",\"routes\":[]}", 500, "OSRM returned HTTP 500 with code 'Ok'."));
    }

    @ParameterizedTest
    @MethodSource("errorResponses")
    void routeAsyncErrorStatusOrCodeIsUnavailable(String json, int status, String expectedMessage) throws IOException {
        RoadRouteResult result = route(json, status);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).isEqualTo(expectedMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":\"Ok\",\"routes\":[]}",
            "{\"code\":\"Ok\"}",
            "{\"code\":\"Ok\",\"routes\":[{\"distance\":-1,\"duration\":60}]}",
            "{\"code\":\"Ok\",\"routes\":[{\"distance\":1000,\"duration\":-1}]}",
            "{\"code\":\"Ok\",\"routes\":[{\"distance\":1000,\"duration\":0}]}"})
    void routeAsyncUnusableRouteSummaryIsUnavailable(String json) throws IOException {
        RoadRouteResult result = route(json, 200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("OSRM returned no usable route summary.");
    }

    @Test
    void routeAsyncZeroLengthRouteSucceeds() throws IOException {
        RoadRouteResult result = route("{\"code\":\"Ok\",\"routes\":[{\"distance\":0,\"duration\":0}]}", 200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.SUCCESS);
        assertThat(result.distanceKm()).isEqualTo(0.0);
        assertThat(result.durationHours()).isEqualTo(0.0);
        assertThat(result.geometry()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[[0,0]]", "[[0,0],[1]]", "[[0,0],[1,95]]", "[[0,0],[181,1]]"})
    void routeAsyncUnusableGeometryIsDroppedButRouteSucceeds(String coordinates) throws IOException {
        RoadRouteResult result = route(
                "{\"code\":\"Ok\",\"routes\":[{\"distance\":1000,\"duration\":60,\"geometry\":{\"type\":\"LineString\",\"coordinates\":"
                        + coordinates + "}}]}",
                200);

        assertThat(result.status()).isEqualTo(RoadRouteStatus.SUCCESS);
        assertThat(result.distanceKm()).isEqualTo(1.0);
        assertThat(result.geometry()).isNull();
    }

    @Test
    void routeAsyncFailureResultsCarryProfileAndDataVersion() throws IOException {
        try (StubOsrm osrm = StubOsrm.responding(400, "{\"code\":\"NoSegment\"}")) {
            var provider = new OsrmRoadRouteProvider(
                    HttpClient.newHttpClient(), osrm.uri(), "truck", "europe-2026-09",
                    OsrmRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, null);

            RoadRouteResult result = provider.routeAsync(createRequest()).join();

            assertThat(result.profile()).isEqualTo("truck");
            assertThat(result.dataVersion()).isEqualTo("europe-2026-09");
        }
    }

    @Test
    void routeAsyncBuildsUriFromBasePathTrimmedProfileAndSnapRadius() throws IOException {
        try (StubOsrm osrm = StubOsrm.responding(400, "{\"code\":\"NoSegment\"}")) {
            var provider = new OsrmRoadRouteProvider(
                    HttpClient.newHttpClient(), URI.create(osrm.uri() + "/osrm"), " truck ", null, 250, null);

            provider.routeAsync(createRequest()).join();

            URI requested = osrm.requestUri.get();
            assertThat(requested.getPath()).isEqualTo("/osrm/route/v1/truck/0,0;1,1");
            assertThat(requested.getRawQuery()).endsWith("&radiuses=250;250");
        }
    }

    @Test
    void routeAsyncRejectsSequenceBelowOne() {
        var provider = createProvider(request -> CompletableFuture.failedFuture(new IOException("not expected")));
        var request = new RoadRouteRequest(0, createRequest().from(), createRequest().to());

        assertThatThrownBy(() -> provider.routeAsync(request).join()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routeAsyncRejectsInvalidEndpointBeforeCallingOsrm() {
        AtomicBoolean called = new AtomicBoolean();
        var provider = createProvider(request -> {
            called.set(true);
            return CompletableFuture.failedFuture(new IOException("not expected"));
        });
        var request = new RoadRouteRequest(
                1,
                new ResolvedLocation(null, null, new Coordinate(0, 91), null, "test"),
                new ResolvedLocation(null, null, new Coordinate(1, 1), null, "test"));

        assertThatThrownBy(() -> provider.routeAsync(request).join())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
        assertThat(called).isFalse();
    }

    static Stream<Arguments> unusableBaseUris() {
        return Stream.of(
                Arguments.of("osrm/v1", "must be absolute"),
                Arguments.of("http://127.0.0.1:5000/?profile=car", "query string or fragment"),
                Arguments.of("http://127.0.0.1:5000/#section", "query string or fragment"));
    }

    @ParameterizedTest
    @MethodSource("unusableBaseUris")
    void constructorRejectsUnusableBaseUri(String uri, String expectedMessage) {
        HttpClient client = HttpClient.newHttpClient();

        assertThatThrownBy(() -> new OsrmRoadRouteProvider(client, URI.create(uri)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void constructorRejectsBlankProfile(String profile) {
        HttpClient client = HttpClient.newHttpClient();

        assertThatThrownBy(() -> new OsrmRoadRouteProvider(
                client, BASE_URI, profile, null, OsrmRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile cannot be blank");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void constructorRejectsInvalidSnapDistance(double snapDistance) {
        HttpClient client = HttpClient.newHttpClient();

        assertThatThrownBy(() -> new OsrmRoadRouteProvider(
                client, BASE_URI, OsrmRoadRouteProvider.DEFAULT_PROFILE, null, snapDistance, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snap distance");
    }

    @Test
    void constructorRejectsNullArguments() {
        HttpClient client = HttpClient.newHttpClient();

        assertThatThrownBy(() -> new OsrmRoadRouteProvider(null, BASE_URI)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OsrmRoadRouteProvider(client, null)).isInstanceOf(NullPointerException.class);
    }

    private static RoadRouteRequest createRequest() {
        return new RoadRouteRequest(
                1,
                new ResolvedLocation(null, null, new Coordinate(0, 0), null, "test"),
                new ResolvedLocation(null, null, new Coordinate(1, 1), null, "test"));
    }

    /** Routes the default request through a loopback server returning one canned OSRM response. */
    private static RoadRouteResult route(String json, int status) throws IOException {
        try (StubOsrm osrm = StubOsrm.responding(status, json)) {
            return new OsrmRoadRouteProvider(HttpClient.newHttpClient(), osrm.uri()).routeAsync(createRequest()).join();
        }
    }

    private static OsrmRoadRouteProvider createProvider(Function<HttpRequest, CompletableFuture<HttpResponse<?>>> send) {
        return new OsrmRoadRouteProvider(new StubHttpClient(send), BASE_URI);
    }

    /** A loopback HTTP server that answers every request with one canned OSRM response, or never answers. */
    private static final class StubOsrm implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-osrm");
            thread.setDaemon(true);
            return thread;
        });
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicReference<URI> requestUri = new AtomicReference<>();

        private StubOsrm(int status, String body, boolean hang) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requestUri.set(exchange.getRequestURI());
                if (hang) {
                    try {
                        released.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        static StubOsrm responding(int status, String body) throws IOException {
            return new StubOsrm(status, body, false);
        }

        static StubOsrm hanging() throws IOException {
            return new StubOsrm(0, "", true);
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
