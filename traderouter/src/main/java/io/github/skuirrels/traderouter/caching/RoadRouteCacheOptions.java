package io.github.skuirrels.traderouter.caching;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Settings for {@link CachingRoadRouteProvider}. Setters return this instance for chaining. The provider reads the
 * settings once, when it is created, so later changes do not affect it.
 */
public final class RoadRouteCacheOptions {
    /**
     * Longest time to live accepted, 100 years. Stores add the time to live to the current time, so a much longer
     * value would overflow on every write.
     */
    public static final Duration MAXIMUM_TIME_TO_LIVE = Duration.ofDays(36_525);

    private String partition;
    private Duration timeToLive;
    private Duration negativeTimeToLive;
    private int coordinateDecimals = 5;
    private boolean cacheGeometry = true;
    private Consumer<Exception> onCacheError;

    /**
     * Creates options with the two required settings.
     *
     * @param partition  see {@link #setPartition(String)}
     * @param timeToLive see {@link #setTimeToLive(Duration)}
     */
    public RoadRouteCacheOptions(String partition, Duration timeToLive) {
        this.partition = partition;
        this.timeToLive = timeToLive;
    }

    /**
     * Name for the provider, profile and road dataset the cached routes come from, for example
     * {@code osrm-driving-europe-261001}.
     */
    public String getPartition() {
        return partition;
    }

    /**
     * Sets the partition. Change it when the provider, profile or road dataset changes, so routes from the old setup
     * are no longer served. It cannot be blank or contain {@code |}.
     */
    public RoadRouteCacheOptions setPartition(String partition) {
        this.partition = partition;
        return this;
    }

    /** How long a successful route is kept from the time it was fetched. */
    public Duration getTimeToLive() {
        return timeToLive;
    }

    /**
     * Sets how long a successful route is kept. Set it from the provider's terms: some allow caching only for a
     * limited number of days, and some not at all. It must be positive and at most {@link #MAXIMUM_TIME_TO_LIVE}.
     */
    public RoadRouteCacheOptions setTimeToLive(Duration timeToLive) {
        this.timeToLive = timeToLive;
        return this;
    }

    /** How long a {@code NO_ROUTE} or {@code OUTSIDE_COVERAGE} result is kept, or null. */
    public Duration getNegativeTimeToLive() {
        return negativeTimeToLive;
    }

    /**
     * Sets how long a {@code NO_ROUTE} or {@code OUTSIDE_COVERAGE} result is kept, or null, the default, to fetch
     * those again every time. {@code UNAVAILABLE} results are never cached.
     */
    public RoadRouteCacheOptions setNegativeTimeToLive(Duration negativeTimeToLive) {
        this.negativeTimeToLive = negativeTimeToLive;
        return this;
    }

    /** Decimal places the endpoint coordinates are rounded to in the key. Defaults to 5, about a metre. */
    public int getCoordinateDecimals() {
        return coordinateDecimals;
    }

    /**
     * Sets the decimal places, from 0 to 10. The default of 5 only absorbs floating-point noise; lower it to share
     * entries between nearby points.
     */
    public RoadRouteCacheOptions setCoordinateDecimals(int coordinateDecimals) {
        this.coordinateDecimals = coordinateDecimals;
        return this;
    }

    /** Whether route geometry is stored. Defaults to true. */
    public boolean isCacheGeometry() {
        return cacheGeometry;
    }

    /**
     * Sets whether route geometry is stored. Without it an entry holds only distance, duration and provenance, which
     * is much smaller, but legs served from the cache have no road geometry and are drawn as straight lines.
     */
    public RoadRouteCacheOptions setCacheGeometry(boolean cacheGeometry) {
        this.cacheGeometry = cacheGeometry;
        return this;
    }

    /** Callback for store failures, or null. */
    public Consumer<Exception> getOnCacheError() {
        return onCacheError;
    }

    /**
     * Sets a callback for when the store throws while reading or writing, or returns a result that cannot be used.
     * The leg is then routed by the wrapped provider as if nothing were cached, so a store outage costs provider
     * calls rather than failing movements. Exceptions thrown by the callback are ignored for the same reason.
     */
    public RoadRouteCacheOptions setOnCacheError(Consumer<Exception> onCacheError) {
        this.onCacheError = onCacheError;
        return this;
    }
}
