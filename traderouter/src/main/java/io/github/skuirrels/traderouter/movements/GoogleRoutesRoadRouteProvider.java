package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.json.JsonParser;
import io.github.skuirrels.traderouter.json.JsonWriter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Routes road legs through the Google Maps Platform Routes API {@code computeRoutes} method.
 * Google's terms restrict caching of most Routes API content and require Routes results drawn on a map to be
 * shown on a Google Map; callers are responsible for complying with them when they store or display results.
 */
public final class GoogleRoutesRoadRouteProvider implements RoadRouteProvider {
    /** Base URI of the public Google Routes API. */
    public static final URI DEFAULT_BASE_URI = URI.create("https://routes.googleapis.com/");

    /** Default Routes API travel mode. */
    public static final String DEFAULT_TRAVEL_MODE = "DRIVE";

    /** Default maximum distance from each endpoint to the start or end of the returned route, in metres. */
    public static final double DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS = 10_000.0;

    private static final String SOURCE_NAME = "google-routes";
    private static final String FIELD_MASK = "routes.distanceMeters,routes.duration,routes.polyline.geoJsonLinestring";
    private static final Pattern DURATION_SECONDS = Pattern.compile("(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)");

    private final HttpClient httpClient;
    private final String apiKey;
    private final URI requestUri;
    private final String travelMode;
    private final double maximumSnapDistanceMeters;
    private final Duration requestTimeout;

    /** Creates a provider for {@code DRIVE} routes on the public API with the default snap distance and no request timeout. */
    public GoogleRoutesRoadRouteProvider(HttpClient httpClient, String apiKey) {
        this(httpClient, apiKey, DEFAULT_TRAVEL_MODE, DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS, null, null);
    }

    /**
     * Creates a Google Routes provider. The client is owned by the caller and should normally be a long-lived
     * shared instance. The API key is sent in the {@code X-Goog-Api-Key} header of each request and never
     * appears in result messages.
     *
     * @param httpClient                caller-owned HTTP client used to call the Routes API
     * @param apiKey                    Google Maps Platform API key with the Routes API enabled
     * @param travelMode                Routes API travel mode, normally {@code DRIVE}. Other modes such as
     *                                  {@code TWO_WHEELER} or {@code TRUCK} may be billed at a higher SKU or need
     *                                  request attributes this adapter does not send.
     * @param maximumSnapDistanceMeters maximum distance from each endpoint to the start or end of the returned
     *                                  route. Google snaps endpoints to roads without a limit, so a larger gap is
     *                                  reported as {@link RoadRouteStatus#OUTSIDE_COVERAGE}.
     * @param baseUri                   base URI of the Routes API, or a proxy in front of it, or null for
     *                                  {@link #DEFAULT_BASE_URI}
     * @param requestTimeout            per-request timeout, or null for none. A timed-out request is reported as
     *                                  {@link RoadRouteStatus#UNAVAILABLE}. The .NET library takes this from
     *                                  {@code HttpClient.Timeout}; the JDK client sets it per request.
     */
    public GoogleRoutesRoadRouteProvider(
            HttpClient httpClient,
            String apiKey,
            String travelMode,
            double maximumSnapDistanceMeters,
            URI baseUri,
            Duration requestTimeout) {
        Objects.requireNonNull(httpClient, "httpClient");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("The Google Maps API key cannot be blank.");
        }
        if (travelMode == null || travelMode.isBlank()) {
            throw new IllegalArgumentException("The Google Routes travel mode cannot be blank.");
        }
        if (!Double.isFinite(maximumSnapDistanceMeters) || maximumSnapDistanceMeters <= 0.0) {
            throw new IllegalArgumentException(
                    "The maximum Google Routes snap distance must be finite and positive. Received: " + maximumSnapDistanceMeters);
        }
        if (baseUri == null) {
            baseUri = DEFAULT_BASE_URI;
        }
        if (!baseUri.isAbsolute()) {
            throw new IllegalArgumentException("The Google Routes base URI must be absolute.");
        }
        if (!"http".equalsIgnoreCase(baseUri.getScheme()) && !"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalArgumentException("The Google Routes base URI must use HTTP or HTTPS.");
        }
        if (baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("The Google Routes base URI cannot contain a query string or fragment.");
        }
        if (requestTimeout != null && (requestTimeout.isNegative() || requestTimeout.isZero())) {
            throw new IllegalArgumentException("The Google Routes request timeout must be positive. Received: " + requestTimeout);
        }

        String absolute = baseUri.toString();
        URI normalizedBase = absolute.endsWith("/") ? baseUri : URI.create(absolute + "/");

        this.httpClient = httpClient;
        this.apiKey = apiKey.strip();
        this.requestUri = normalizedBase.resolve("directions/v2:computeRoutes");
        this.travelMode = travelMode.strip().toUpperCase(Locale.ROOT);
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
        Coordinate from = normalizeLongitude(request.from().coordinate());
        Coordinate to = normalizeLongitude(request.to().coordinate());

        JsonWriter body = new JsonWriter(false).beginObject();
        writeWaypoint(body.name("origin"), from);
        writeWaypoint(body.name("destination"), to);
        body.name("travelMode").value(travelMode)
                .name("polylineQuality").value("HIGH_QUALITY")
                .name("polylineEncoding").value("GEO_JSON_LINESTRING")
                .endObject();

        HttpRequest.Builder builder = HttpRequest.newBuilder(requestUri)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", FIELD_MASK);
        if (requestTimeout != null) {
            builder.timeout(requestTimeout);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> error == null ? read(response, from, to) : fromError(error));
    }

    private RoadRouteResult read(HttpResponse<String> response, Coordinate from, Coordinate to) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return unavailable(readError(response));
        }

        Object parsed;
        try {
            parsed = JsonParser.parse(response.body());
        } catch (RuntimeException ex) {
            return unavailable("Google Routes returned invalid JSON: " + ex.getMessage());
        }
        if (parsed == null) {
            return unavailable("Google Routes returned an empty response.");
        }
        if (!(parsed instanceof Map<?, ?>)) {
            return unavailable("Google Routes returned invalid JSON: expected an object.");
        }

        Object routes = JsonParser.asObject(parsed).get("routes");
        if (routes != null && !(routes instanceof List<?>)) {
            return unavailable("Google Routes returned invalid JSON: routes is not an array.");
        }
        Object first = routes == null || ((List<?>) routes).isEmpty() ? null : ((List<?>) routes).get(0);
        if (first == null) {
            return failure(RoadRouteStatus.NO_ROUTE, "Google Routes found no route between the endpoints.");
        }
        if (!(first instanceof Map<?, ?>)) {
            return unavailable("Google Routes returned invalid JSON: a route is not an object.");
        }
        Map<String, Object> route = JsonParser.asObject(first);

        // Protobuf JSON omits zero values, so a missing distance or duration on a returned route means zero.
        Object distanceValue = route.get("distanceMeters");
        Object durationValue = route.get("duration");
        Double distanceMeters = distanceValue == null ? Double.valueOf(0.0)
                : JsonParser.isNumber(distanceValue) ? Double.valueOf(JsonParser.asDouble(distanceValue)) : null;
        Double seconds = durationValue == null ? Double.valueOf(0.0)
                : durationValue instanceof String s ? parseDuration(s) : null;
        if (distanceMeters == null || !Double.isFinite(distanceMeters) || distanceMeters < 0.0
                || seconds == null || seconds < 0.0
                || (distanceMeters > 0.0 && seconds <= 0.0)) {
            return unavailable("Google Routes returned no usable route summary.");
        }

        List<Coordinate> geometry = readGeometry(route.get("polyline"));
        if (geometry == null) {
            return unavailable("Google Routes returned no usable route geometry.");
        }

        double startGapMeters = Haversine.distanceKm(from, geometry.get(0)) * 1000.0;
        double endGapMeters = Haversine.distanceKm(to, geometry.get(geometry.size() - 1)) * 1000.0;
        if (startGapMeters > maximumSnapDistanceMeters || endGapMeters > maximumSnapDistanceMeters) {
            return failure(
                    RoadRouteStatus.OUTSIDE_COVERAGE,
                    String.format(Locale.ROOT, "Google Routes snapped an endpoint %.0f m away, beyond the %s m limit.",
                            Math.max(startGapMeters, endGapMeters), JsonWriter.formatDouble(maximumSnapDistanceMeters)));
        }

        return RoadRouteResult.builder(RoadRouteStatus.SUCCESS, SOURCE_NAME)
                .distanceKm(distanceMeters / 1000.0)
                .durationHours(seconds / 3600.0)
                .geometry(geometry)
                .profile(travelMode)
                .build();
    }

    private RoadRouteResult fromError(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof CancellationException cancellation) {
            throw cancellation;
        }
        if (cause instanceof HttpTimeoutException) {
            return unavailable("The Google Routes request timed out.");
        }
        return unavailable("Google Routes was unavailable: " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
    }

    private RoadRouteResult failure(RoadRouteStatus status, String message) {
        return RoadRouteResult.builder(status, SOURCE_NAME)
                .profile(travelMode)
                .message(message)
                .build();
    }

    private RoadRouteResult unavailable(String message) {
        return failure(RoadRouteStatus.UNAVAILABLE, message);
    }

    /** Reads Google's error status and message without ever echoing the request, so the key cannot leak. */
    private static String readError(HttpResponse<String> response) {
        String prefix = "Google Routes returned HTTP " + response.statusCode();
        try {
            if (!(JsonParser.parse(response.body()) instanceof Map<?, ?> root)
                    || !(JsonParser.asObject(root).get("error") instanceof Map<?, ?> error)) {
                return prefix + ".";
            }
            Map<String, Object> fields = JsonParser.asObject(error);
            Object status = fields.get("status");
            Object message = fields.get("message");
            if ((status != null && !(status instanceof String)) || (message != null && !(message instanceof String))) {
                return prefix + ".";
            }
            String statusText = status == null || ((String) status).isBlank() ? "" : " " + status;
            String detail = message == null || ((String) message).isBlank() ? "" : ": " + message;
            return prefix + statusText + detail;
        } catch (RuntimeException ex) {
            return prefix + ".";
        }
    }

    /** Parses a protobuf JSON duration such as {@code "7200s"}, accepting only digits and one decimal point. */
    private static Double parseDuration(String value) {
        if (!value.endsWith("s")) {
            return null;
        }
        String number = value.substring(0, value.length() - 1);
        return DURATION_SECONDS.matcher(number).matches() ? Double.valueOf(number) : null;
    }

    private static Coordinate normalizeLongitude(Coordinate coordinate) {
        return coordinate.longitude() >= -180.0 && coordinate.longitude() <= 180.0
                ? coordinate
                : coordinate.withNormalizedLongitude();
    }

    private static void writeWaypoint(JsonWriter writer, Coordinate coordinate) {
        writer.beginObject()
                .name("location").beginObject()
                .name("latLng").beginObject()
                .name("latitude").value(coordinate.latitude())
                .name("longitude").value(coordinate.longitude())
                .endObject()
                .endObject()
                .endObject();
    }

    private static List<Coordinate> readGeometry(Object polyline) {
        if (!(polyline instanceof Map<?, ?>)
                || !(JsonParser.asObject(polyline).get("geoJsonLinestring") instanceof Map<?, ?> lineString)
                || !(JsonParser.asObject(lineString).get("coordinates") instanceof List<?> positions)
                || positions.size() < 2) {
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
}
