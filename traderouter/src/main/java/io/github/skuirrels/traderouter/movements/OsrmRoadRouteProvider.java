package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.json.JsonParser;
import io.github.skuirrels.traderouter.json.JsonWriter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Routes road legs through an OSRM HTTP service, including a locally hosted OSRM container. */
public final class OsrmRoadRouteProvider implements RoadRouteProvider {
    /** Default OSRM routing profile. */
    public static final String DEFAULT_PROFILE = "driving";

    /** Default maximum distance from each endpoint to a routable road, in metres. */
    public static final double DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS = 10_000.0;

    private static final String SERVICE_NAME = "OSRM";

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
     * @param requestTimeout            per-request timeout covering the whole exchange including the response
     *                                  body, or null for none. A timed-out request is reported as
     *                                  {@link RoadRouteStatus#UNAVAILABLE}. The .NET library takes this from
     *                                  {@code HttpClient.Timeout}.
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
        URI normalizedBase = RoadProviderHttp.normalizeBaseUri(baseUri, SERVICE_NAME);
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("The OSRM profile cannot be blank.");
        }
        RoadProviderHttp.validateSnapDistance(maximumSnapDistanceMeters, SERVICE_NAME);
        RoadProviderHttp.validateTimeout(requestTimeout, SERVICE_NAME);

        this.httpClient = httpClient;
        this.baseUri = normalizedBase;
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
        Coordinate from = RoadProviderHttp.normalizeLongitude(request.from().coordinate());
        Coordinate to = RoadProviderHttp.normalizeLongitude(request.to().coordinate());
        String coordinates = format(from.longitude()) + "," + format(from.latitude()) + ";"
                + format(to.longitude()) + "," + format(to.latitude());
        String radius = format(maximumSnapDistanceMeters);
        URI requestUri = baseUri.resolve(
                "route/v1/" + URLEncoder.encode(profile, StandardCharsets.UTF_8).replace("+", "%20") + "/" + coordinates
                        + "?overview=full&geometries=geojson&steps=false&radiuses=" + radius + ";" + radius);

        HttpRequest httpRequest = HttpRequest.newBuilder(requestUri).GET().header("Accept", "application/json").build();

        return RoadProviderHttp.send(httpClient, httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), requestTimeout)
                .handle((response, error) -> error == null
                        ? read(response)
                        : unavailable(RoadProviderHttp.failureMessage(error, SERVICE_NAME)));
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
                .geometry(RoadProviderHttp.readLineString(route.get("geometry")))
                .profile(profile)
                .dataVersion(dataVersion)
                .build();
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

    /** Shortest round-trip form without a trailing ".0", as the .NET "R" format writes the request. */
    private static String format(double value) {
        return JsonWriter.formatDouble(value);
    }
}
