package io.github.skuirrels.traderouter.movements;

import java.util.concurrent.CompletableFuture;

/**
 * Supplies road-network distance, duration and geometry for resolved movement legs. The .NET library calls
 * this interface {@code IRoadRouteProvider}.
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
