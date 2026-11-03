package io.github.skuirrels.traderouter.caching;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * A byte store keyed by string with an absolute expiry per entry, such as Redis, a SQL table or an in-process cache,
 * for {@link DistributedRoadRouteCache}. It stands in for the .NET {@code IDistributedCache}, keeping only the two
 * operations the road-route cache needs, so an adapter over an existing client is a few lines.
 *
 * <p>Implementations must be thread-safe. Throw, or complete the future exceptionally, when the store fails.
 */
public interface DistributedCache {
    /** Completes with the bytes stored under the key, or with null when there are none or they have expired. */
    CompletableFuture<byte[]> getAsync(String key);

    /**
     * Stores the bytes under the key, replacing any earlier value, and removes them once the time to live has
     * passed. The time to live is counted from this call and is not extended when the entry is read.
     */
    CompletableFuture<Void> setAsync(String key, byte[] value, Duration timeToLive);
}
