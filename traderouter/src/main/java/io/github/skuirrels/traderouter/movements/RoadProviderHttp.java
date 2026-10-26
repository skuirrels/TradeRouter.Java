package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.json.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Validation, transport and GeoJSON handling shared by the HTTP road route providers. */
final class RoadProviderHttp {
    private RoadProviderHttp() {
    }

    /**
     * Validates a provider base URI and returns it with a trailing slash, so relative paths resolve beneath it.
     * Opaque and host-less URIs are rejected here because the JDK client cannot send to them.
     */
    static URI normalizeBaseUri(URI baseUri, String service) {
        if (!baseUri.isAbsolute()) {
            throw new IllegalArgumentException("The " + service + " base URI must be absolute.");
        }
        if (!"http".equalsIgnoreCase(baseUri.getScheme()) && !"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalArgumentException("The " + service + " base URI must use HTTP or HTTPS.");
        }
        if (baseUri.isOpaque() || baseUri.getHost() == null) {
            throw new IllegalArgumentException("The " + service + " base URI must name a host.");
        }
        if (baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("The " + service + " base URI cannot contain a query string or fragment.");
        }
        String absolute = baseUri.toString();
        return absolute.endsWith("/") ? baseUri : URI.create(absolute + "/");
    }

    static void validateSnapDistance(double maximumSnapDistanceMeters, String service) {
        if (!Double.isFinite(maximumSnapDistanceMeters) || maximumSnapDistanceMeters <= 0.0) {
            throw new IllegalArgumentException(
                    "The maximum " + service + " snap distance must be finite and positive. Received: " + maximumSnapDistanceMeters);
        }
    }

    static void validateTimeout(Duration requestTimeout, String service) {
        if (requestTimeout != null && (requestTimeout.isNegative() || requestTimeout.isZero())) {
            throw new IllegalArgumentException("The " + service + " request timeout must be positive. Received: " + requestTimeout);
        }
    }

    /**
     * Sends a request whose timeout, when set, covers the whole exchange including the body, as the .NET
     * {@code HttpClient.Timeout} does. On JDK 21 {@link HttpRequest.Builder#timeout} stops once response headers
     * arrive, so a body that stalls would otherwise never time out.
     */
    static <T> CompletableFuture<HttpResponse<T>> send(
            HttpClient httpClient, HttpRequest request, HttpResponse.BodyHandler<T> handler, Duration requestTimeout) {
        CompletableFuture<HttpResponse<T>> pending = httpClient.sendAsync(request, handler);
        if (requestTimeout == null) {
            return pending;
        }
        CompletableFuture<HttpResponse<T>> timed = pending.copy().orTimeout(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        timed.whenComplete((response, error) -> {
            if (error instanceof TimeoutException) {
                pending.cancel(true);
            }
        });
        return timed;
    }

    /**
     * Describes a failed exchange as {@code "The <service> request timed out."} or
     * {@code "<service> was unavailable: <reason>"}, rethrowing cancellation requested by the caller.
     */
    static String failureMessage(Throwable error, String service) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof CancellationException cancellation) {
            throw cancellation;
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return "The " + service + " request timed out.";
        }
        return service + " was unavailable: " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
    }

    /** Normalizes a longitude outside [-180, 180], which road services reject. */
    static Coordinate normalizeLongitude(Coordinate coordinate) {
        return coordinate.longitude() >= -180.0 && coordinate.longitude() <= 180.0
                ? coordinate
                : coordinate.withNormalizedLongitude();
    }

    /**
     * Reads the positions of a GeoJSON LineString object, or returns null when it is missing, has fewer than two
     * positions, or holds a position that is not a valid longitude and latitude.
     */
    static List<Coordinate> readLineString(Object lineString) {
        if (!(lineString instanceof Map<?, ?>)
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
