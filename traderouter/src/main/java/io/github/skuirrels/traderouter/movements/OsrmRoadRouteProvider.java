package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.json.JsonParser;
import io.github.skuirrels.traderouter.json.JsonWriter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Routes road legs through an OSRM HTTP service, including a locally hosted OSRM container. */
public final class OsrmRoadRouteProvider implements RoadRouteProvider {
    /** Default OSRM routing profile. */
    public static final String DEFAULT_PROFILE = "driving";

    /** Default maximum distance from each endpoint to a routable road, in metres. */
    public static final double DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS = 10_000.0;

    private final HttpClient httpClient;
    private final URI baseUri;
    private final String profile;
    private final String dataVersion;
    private final double maximumSnapDistanceMeters;
    private final Duration requestTimeout;

    /** Creates a provider for the {@code driving} profile with the default snap distance and no request timeout. */
    public OsrmRoadRouteProvider(HttpClient httpClient, URI baseUri) {
        this(httpClient, baseUri, DEFAULT_PROFILE, null, DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, null);
    }

    /**
     * Creates an OSRM provider. The client is owned by the caller and should normally be a long-lived shared
     * instance.
     *
     * @param httpClient                caller-owned HTTP client used to call OSRM
     * @param baseUri                   base URI of the running OSRM service
     * @param profile                   OSRM routing profile name, normally {@code driving}
     * @param dataVersion               optional caller-defined provenance label for the OSM extract loaded into the
     *                                  service, or null. It is copied to results but is not sent to OSRM and does
     *                                  not select or load a dataset.
     * @param maximumSnapDistanceMeters maximum distance from each endpoint to a routable road
     * @param requestTimeout            per-request timeout, or null for none. A timed-out request is reported as
     *                                  {@link RoadRouteStatus#UNAVAILABLE}. The .NET library takes this from
     *                                  {@code HttpClient.Timeout}; the JDK client sets it per request.
     */
    public OsrmRoadRouteProvider(
            HttpClient httpClient,
            URI baseUri,
            String profile,
            String dataVersion,
            double maximumSnapDistanceMeters,
            Duration requestTimeout) {
        Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(baseUri, "baseUri");
        if (!baseUri.isAbsolute()) {
            throw new IllegalArgumentException("The OSRM base URI must be absolute.");
        }
        if (!"http".equalsIgnoreCase(baseUri.getScheme()) && !"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalArgumentException("The OSRM base URI must use HTTP or HTTPS.");
        }
        if (baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("The OSRM base URI cannot contain a query string or fragment.");
        }
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("The OSRM profile cannot be blank.");
        }
        if (!Double.isFinite(maximumSnapDistanceMeters) || maximumSnapDistanceMeters <= 0.0) {
            throw new IllegalArgumentException(
                    "The maximum OSRM snap distance must be finite and positive. Received: " + maximumSnapDistanceMeters);
        }
        if (requestTimeout != null && (requestTimeout.isNegative() || requestTimeout.isZero())) {
            throw new IllegalArgumentException("The OSRM request timeout must be positive. Received: " + requestTimeout);
        }

        this.httpClient = httpClient;
        String absolute = baseUri.toString();
        this.baseUri = absolute.endsWith("/") ? baseUri : URI.create(absolute + "/");
        this.profile = profile.trim();
        this.dataVersion = dataVersion == null || dataVersion.isBlank() ? null : dataVersion.trim();
        this.maximumSnapDistanceMeters = maximumSnapDistanceMeters;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public CompletableFuture<RoadRouteResult> routeAsync(RoadRouteRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.sequence() < 1) {
            throw new IllegalArgumentException("Road leg sequence must be at least one. Received: " + request.sequence());
        }

        request.from().coordinate().validate();
        request.to().coordinate().validate();
        Coordinate from = normalizeForOsrm(request.from().coordinate());
        Coordinate to = normalizeForOsrm(request.to().coordinate());
        String coordinates = format(from.longitude()) + "," + format(from.latitude()) + ";"
                + format(to.longitude()) + "," + format(to.latitude());
        String radius = format(maximumSnapDistanceMeters);
        URI requestUri = baseUri.resolve(
                "route/v1/" + URLEncoder.encode(profile, StandardCharsets.UTF_8).replace("+", "%20") + "/" + coordinates
                        + "?overview=full&geometries=geojson&steps=false&radiuses=" + radius + ";" + radius);

        HttpRequest.Builder builder = HttpRequest.newBuilder(requestUri).GET().header("Accept", "application/json");
        if (requestTimeout != null) {
            builder.timeout(requestTimeout);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> error == null ? read(response) : fromError(error));
    }

    private RoadRouteResult read(HttpResponse<String> response) {
        Map<String, Object> payload;
        try {
            Object parsed = JsonParser.parse(response.body());
            if (!(parsed instanceof Map<?, ?>)) {
                return unavailable("OSRM returned an empty response.");
            }
            payload = JsonParser.asObject(parsed);
        } catch (RuntimeException ex) {
            return unavailable("OSRM returned invalid JSON: " + ex.getMessage());
        }

        String code = payload.get("code") instanceof String s ? s : null;
        String message = payload.get("message") instanceof String s ? s : null;
        if ("NoSegment".equalsIgnoreCase(code)) {
            return failure(RoadRouteStatus.OUTSIDE_COVERAGE,
                    message != null ? message : "OSRM could not match an endpoint to its loaded road network.");
        }
        if ("NoRoute".equalsIgnoreCase(code)) {
            return failure(RoadRouteStatus.NO_ROUTE,
                    message != null ? message : "OSRM found no traversable route between the endpoints.");
        }
        boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
        if (!success || !"Ok".equalsIgnoreCase(code)) {
            return unavailable(message != null ? message
                    : "OSRM returned HTTP " + response.statusCode() + " with code '" + (code != null ? code : "unknown") + "'.");
        }

        Map<String, Object> route = payload.get("routes") instanceof List<?> routes && !routes.isEmpty()
                && routes.get(0) instanceof Map<?, ?>
                ? JsonParser.asObject(routes.get(0))
                : null;
        Double distance = route != null && JsonParser.isNumber(route.get("distance")) ? JsonParser.asDouble(route.get("distance")) : null;
        Double duration = route != null && JsonParser.isNumber(route.get("duration")) ? JsonParser.asDouble(route.get("duration")) : null;
        if (distance == null || !Double.isFinite(distance) || distance < 0.0
                || duration == null || !Double.isFinite(duration) || duration < 0.0
                || (distance > 0.0 && duration <= 0.0)) {
            return unavailable("OSRM returned no usable route summary.");
        }

        return RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm")
                .distanceKm(distance / 1000.0)
                .durationHours(duration / 3600.0)
                .geometry(readGeometry(route.get("geometry")))
                .profile(profile)
                .dataVersion(dataVersion)
                .build();
    }

    private RoadRouteResult fromError(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof CancellationException cancellation) {
            throw cancellation;
        }
        if (cause instanceof HttpTimeoutException) {
            return unavailable("The OSRM request timed out.");
        }
        return unavailable("OSRM was unavailable: " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
    }

    private RoadRouteResult failure(RoadRouteStatus status, String message) {
        return RoadRouteResult.builder(status, "osrm")
                .profile(profile)
                .dataVersion(dataVersion)
                .message(message)
                .build();
    }

    private RoadRouteResult unavailable(String message) {
        return failure(RoadRouteStatus.UNAVAILABLE, message);
    }

    private static Coordinate normalizeForOsrm(Coordinate coordinate) {
        return coordinate.longitude() >= -180.0 && coordinate.longitude() <= 180.0
                ? coordinate
                : coordinate.withNormalizedLongitude();
    }

    private static List<Coordinate> readGeometry(Object geometry) {
        if (!(geometry instanceof Map<?, ?>)) {
            return null;
        }
        if (!(JsonParser.asObject(geometry).get("coordinates") instanceof List<?> positions) || positions.size() < 2) {
            return null;
        }

        List<Coordinate> coordinates = new ArrayList<>(positions.size());
        for (Object position : positions) {
            if (!(position instanceof List<?> pair) || pair.size() < 2
                    || !JsonParser.isNumber(pair.get(0)) || !JsonParser.isNumber(pair.get(1))) {
                return null;
            }
            double lon = JsonParser.asDouble(pair.get(0));
            double lat = JsonParser.asDouble(pair.get(1));
            if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0 || !Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
                return null;
            }
            coordinates.add(new Coordinate(lon, lat));
        }
        return coordinates;
    }

    /** Shortest round-trip form without a trailing ".0", as the .NET "R" format writes the request. */
    private static String format(double value) {
        return JsonWriter.formatDouble(value);
    }
}
