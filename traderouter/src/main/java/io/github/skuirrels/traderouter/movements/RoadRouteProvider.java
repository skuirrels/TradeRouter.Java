package io.github.skuirrels.traderouter.movements;

import java.util.concurrent.CompletableFuture;

/**
 * Supplies road-network distance, duration and geometry for resolved movement legs. The library includes
 * {@link OsrmRoadRouteProvider} and {@link GoogleRoutesRoadRouteProvider}; implement this interface to use any
 * other routing service. The .NET library calls this interface {@code IRoadRouteProvider}.
 *
 * <p>Implementations should complete the future with a result rather than exceptionally for service outcomes:
 * {@link RoadRouteStatus#SUCCESS} with a non-negative {@link RoadRouteResult#distanceKm()},
 * {@link RoadRouteStatus#OUTSIDE_COVERAGE} when an endpoint cannot be matched to the provider's road network,
 * {@link RoadRouteStatus#NO_ROUTE} when the endpoints are covered but not connected, and
 * {@link RoadRouteStatus#UNAVAILABLE} for timeouts, transport failures and unusable responses.
 * {@link RoadRoutingMode#PREFER_NETWORK_THEN_ESTIMATE} falls back to the estimator only for
 * {@link RoadRouteStatus#OUTSIDE_COVERAGE} and {@link RoadRouteStatus#UNAVAILABLE}. Set
 * {@link RoadRouteResult#source()} to a stable identifier for the service. Let cancellation of the returned
 * future propagate. One instance may be shared by concurrent movement calculations, so implementations must be
 * thread-safe.
 */
@FunctionalInterface
public interface RoadRouteProvider {
    /**
     * Routes one road leg without blocking the calling thread. A provider should complete the future with a
     * non-success {@link RoadRouteResult} rather than exceptionally when the service is unavailable. Cancelling
     * the returned future abandons the request.
     */
    CompletableFuture<RoadRouteResult> routeAsync(RoadRouteRequest request);
}
