package io.github.skuirrels.traderouter.caching;

import io.github.skuirrels.traderouter.movements.RoadRouteProvider;
import io.github.skuirrels.traderouter.movements.RoadRouteRequest;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRouteStatus;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Wraps a road-route provider and serves repeated legs from a {@link RoadRouteCache}. Successful routes are kept for
 * {@link RoadRouteCacheOptions#getTimeToLive()} from the time they were fetched, {@code NO_ROUTE} and
 * {@code OUTSIDE_COVERAGE} results only when {@link RoadRouteCacheOptions#getNegativeTimeToLive()} is set, and
 * {@code UNAVAILABLE} results never.
 *
 * <p>Concurrent requests for the same uncached leg each call the wrapped provider. Cancelling the returned future
 * cancels the store or provider call in progress.
 */
public final class CachingRoadRouteProvider implements RoadRouteProvider {
    private final RoadRouteProvider inner;
    private final RoadRouteCache cache;
    private final String partition;
    private final Duration timeToLive;
    private final Duration negativeTimeToLive;
    private final int coordinateDecimals;
    private final boolean cacheGeometry;
    private final Consumer<Exception> onCacheError;

    /**
     * Creates a caching provider.
     *
     * @param inner   provider that routes legs not found in the cache
     * @param cache   store for the results
     * @param options partition, times to live and key settings, read once here
     * @throws IllegalArgumentException when an option is invalid
     */
    public CachingRoadRouteProvider(RoadRouteProvider inner, RoadRouteCache cache, RoadRouteCacheOptions options) {
        Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(options, "options");
        RoadRouteCacheKey.validatePartition(options.getPartition());
        validateTimeToLive(options.getTimeToLive(), "timeToLive");
        if (options.getNegativeTimeToLive() != null) {
            validateTimeToLive(options.getNegativeTimeToLive(), "negativeTimeToLive");
        }
        RoadRouteCacheKey.validateCoordinateDecimals(options.getCoordinateDecimals());

        this.inner = inner;
        this.cache = cache;
        this.partition = options.getPartition().trim();
        this.timeToLive = options.getTimeToLive();
        this.negativeTimeToLive = options.getNegativeTimeToLive();
        this.coordinateDecimals = options.getCoordinateDecimals();
        this.cacheGeometry = options.isCacheGeometry();
        this.onCacheError = options.getOnCacheError();
    }

    @Override
    public CompletableFuture<RoadRouteResult> routeAsync(RoadRouteRequest request) {
        Objects.requireNonNull(request, "request");
        RoadRouteCacheKey key = RoadRouteCacheKey.createValidated(partition, request, coordinateDecimals);

        CompletableFuture<RoadRouteResult> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();
        result.whenComplete((ignored, error) -> {
            CompletableFuture<?> pending = inFlight.get();
            if (result.isCancelled() && pending != null) {
                pending.cancel(true);
            }
        });

        CompletableFuture<RoadRouteResult> read;
        try {
            read = Objects.requireNonNull(cache.getAsync(key), "The road-route cache returned a null future.");
        } catch (RuntimeException ex) {
            reportCacheError(ex);
            read = CompletableFuture.completedFuture(null);
        }

        continueWith(read, inFlight, result, (cached, error) -> {
            if (error != null) {
                if (!reportStoreFailure(error, result)) {
                    return;
                }
            } else if (cached != null) {
                if (isUsable(cached)) {
                    result.complete(cached);
                    return;
                }
                reportCacheError(new IllegalStateException(
                        "The cache returned an unusable " + cached.status().displayName() + " result for '"
                                + key.value() + "'; the leg was routed again."));
            }
            routeAndStore(request, key, inFlight, result);
        });
        return result;
    }

    private void routeAndStore(
            RoadRouteRequest request,
            RoadRouteCacheKey key,
            AtomicReference<CompletableFuture<?>> inFlight,
            CompletableFuture<RoadRouteResult> result) {
        CompletableFuture<RoadRouteResult> call;
        try {
            call = inner.routeAsync(request);
        } catch (RuntimeException | Error ex) {
            result.completeExceptionally(ex);
            return;
        }
        if (call == null) {
            result.completeExceptionally(new IllegalStateException(
                    "Road-route provider returned null for leg " + request.sequence() + "."));
            return;
        }

        continueWith(call, inFlight, result, (routed, error) -> {
            if (error != null) {
                result.completeExceptionally(unwrap(error));
                return;
            }
            // A result the engine would reject is not stored, so later reads do not report it as a cache fault.
            Duration expiry = routed == null || !isUsable(routed) ? null : timeToLiveFor(routed);
            if (expiry == null) {
                result.complete(routed);
                return;
            }
            store(key, routed, expiry, inFlight, result);
        });
    }

    private void store(
            RoadRouteCacheKey key,
            RoadRouteResult routed,
            Duration expiry,
            AtomicReference<CompletableFuture<?>> inFlight,
            CompletableFuture<RoadRouteResult> result) {
        CompletableFuture<Void> write;
        try {
            RoadRouteResult stored = cacheGeometry ? routed : new RoadRouteResult(
                    routed.status(), routed.distanceKm(), routed.durationHours(), null,
                    routed.source(), routed.profile(), routed.dataVersion(), routed.message());
            write = Objects.requireNonNull(cache.setAsync(key, stored, expiry), "The road-route cache returned a null future.");
        } catch (RuntimeException ex) {
            reportCacheError(ex);
            result.complete(routed);
            return;
        }

        continueWith(write, inFlight, result, (ignored, error) -> {
            if (error == null || reportStoreFailure(error, result)) {
                result.complete(routed);
            }
        });
    }

    // Records the stage in progress so cancelling the result cancels it, including a cancellation that raced ahead
    // of the stage starting, then runs the next step unless the result is already settled. A step that throws fails
    // the result rather than leaving it incomplete.
    private static <T> void continueWith(
            CompletableFuture<T> stage,
            AtomicReference<CompletableFuture<?>> inFlight,
            CompletableFuture<RoadRouteResult> result,
            BiConsumer<T, Throwable> step) {
        inFlight.set(stage);
        if (result.isCancelled()) {
            stage.cancel(true);
        }
        stage.whenComplete((value, error) -> {
            if (result.isDone()) {
                return;
            }
            try {
                step.accept(value, error);
            } catch (Throwable ex) {
                result.completeExceptionally(ex);
            }
        });
    }

    private Duration timeToLiveFor(RoadRouteResult result) {
        return switch (result.status()) {
            case SUCCESS -> result.distanceKm() != null ? timeToLive : null;
            case NO_ROUTE, OUTSIDE_COVERAGE -> negativeTimeToLive;
            case UNAVAILABLE -> null;
        };
    }

    // A stored success must pass the checks RoadLegs applies to a provider's result, so a bad entry is routed again
    // rather than failing the movement. TradeRouter.Net 2.9.0 omits the positive-duration check. A test runs the
    // same results through the engine, so the two stay in step.
    static boolean isUsable(RoadRouteResult result) {
        return switch (result.status()) {
            case SUCCESS -> {
                Double distance = result.distanceKm();
                Double duration = result.durationHours();
                yield distance != null && Double.isFinite(distance) && distance >= 0.0
                        && (duration == null || (Double.isFinite(duration) && duration >= 0.0))
                        && !(distance > 0.0 && duration != null && duration <= 0.0)
                        && (result.geometry() == null || result.geometry().size() != 1)
                        && !result.source().isBlank();
            }
            case NO_ROUTE, OUTSIDE_COVERAGE -> !result.source().isBlank();
            case UNAVAILABLE -> false;
        };
    }

    // Reports a failed store call and returns true so the leg carries on without the cache, or, for an Error such as
    // OutOfMemoryError, fails the result and returns false.
    private boolean reportStoreFailure(Throwable error, CompletableFuture<RoadRouteResult> result) {
        Throwable cause = unwrap(error);
        if (cause instanceof Exception exception) {
            reportCacheError(exception);
            return true;
        }
        result.completeExceptionally(cause);
        return false;
    }

    // The callback is for reporting only; an exception from it must not fail the movement the cache was saving.
    private void reportCacheError(Exception ex) {
        if (onCacheError == null) {
            return;
        }
        try {
            onCacheError.accept(ex);
        } catch (RuntimeException ignored) {
            // Ignored by design.
        }
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    static void validateTimeToLive(Duration timeToLive, String name) {
        if (timeToLive == null || timeToLive.isNegative() || timeToLive.isZero()
                || timeToLive.compareTo(RoadRouteCacheOptions.MAXIMUM_TIME_TO_LIVE) > 0) {
            throw new IllegalArgumentException(timeToLiveMessage(name, timeToLive));
        }
    }

    private static String timeToLiveMessage(String name, Duration timeToLive) {
        return String.format(Locale.ROOT, "A cache time to live (%s) must be positive and at most %,d days. Received: %s",
                name, RoadRouteCacheOptions.MAXIMUM_TIME_TO_LIVE.toDays(), timeToLive);
    }
}
