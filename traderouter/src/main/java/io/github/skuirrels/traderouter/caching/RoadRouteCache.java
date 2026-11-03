package io.github.skuirrels.traderouter.caching;

import io.github.skuirrels.traderouter.movements.RoadRouteResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Stores road-route results for {@link CachingRoadRouteProvider}. {@link DistributedRoadRouteCache} stores them in
 * any {@link DistributedCache}; implement this interface to keep them somewhere else, such as a database table you
 * want to query. The .NET library calls this interface {@code IRoadRouteCache}.
 *
 * <p>One instance may be shared by concurrent movement calculations, so implementations must be thread-safe. Report
 * store failures by throwing or by completing the future exceptionally rather than hiding them: the caching provider
 * reports them and routes the leg without the cache.
 */
public interface RoadRouteCache {
    /** Completes with the stored result for the key, or with null when there is none or it has expired. */
    CompletableFuture<RoadRouteResult> getAsync(RoadRouteCacheKey key);

    /** Stores a result for the key, replacing any earlier one, and removes it once the time to live has passed. */
    CompletableFuture<Void> setAsync(RoadRouteCacheKey key, RoadRouteResult result, Duration timeToLive);
}
