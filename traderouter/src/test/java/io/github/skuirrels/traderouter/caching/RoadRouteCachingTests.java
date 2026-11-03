package io.github.skuirrels.traderouter.caching;

import io.github.skuirrels.traderouter.DefaultTradeRouterEngine;
import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.RoadRouteProvider;
import io.github.skuirrels.traderouter.movements.RoadRouteRequest;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRouteStatus;
import io.github.skuirrels.traderouter.movements.RoadRoutingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoadRouteCachingTests {
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);

    @Test
    void routeAsyncServesARepeatedLegFromTheCache() {
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new DistributedRoadRouteCache(new MemoryCache()));

        RoadRouteResult first = provider.routeAsync(createRequest(1)).join();
        RoadRouteResult second = provider.routeAsync(createRequest(3)).join();

        assertThat(inner.calls()).as("the leg sequence is not part of the key").isEqualTo(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void routeAsyncKeepsDifferentEndpointsAndPartitionsApart() {
        var inner = new CountingProvider(success());
        var store = new DistributedRoadRouteCache(new MemoryCache());
        var provider = createProvider(inner, store);
        var otherPartition = createProvider(inner, store, options -> options.setPartition("osrm-driving-other"));

        provider.routeAsync(createRequest()).join();
        provider.routeAsync(createRequest(new Coordinate(0, 0), new Coordinate(1, 1.001))).join();
        provider.routeAsync(createRequest(new Coordinate(1, 1), new Coordinate(0, 0))).join();
        otherPartition.routeAsync(createRequest()).join();

        assertThat(inner.calls()).isEqualTo(4);
    }

    @Test
    void routeAsyncSharesAnEntryBetweenCoordinatesThatRoundTogether() {
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new DistributedRoadRouteCache(new MemoryCache()),
                options -> options.setCoordinateDecimals(3));

        provider.routeAsync(createRequest(new Coordinate(-0.0001, 0.0002), new Coordinate(1, 1))).join();
        provider.routeAsync(createRequest(new Coordinate(0.0001, -0.0002), new Coordinate(1, 1))).join();

        assertThat(inner.calls()).as("both origins round to 0,0 at three decimal places").isEqualTo(1);
    }

    @Test
    void routeAsyncStoresSuccessForTheTimeToLive() {
        var store = new RecordingStore(null);
        var provider = createProvider(new CountingProvider(success()), store);

        provider.routeAsync(createRequest()).join();

        assertThat(store.writes).singleElement().extracting(Write::timeToLive).isEqualTo(THIRTY_DAYS);
    }

    @ParameterizedTest
    @EnumSource(value = RoadRouteStatus.class, names = {"NO_ROUTE", "OUTSIDE_COVERAGE"})
    void routeAsyncStoresNegativeResultsOnlyWhenANegativeTimeToLiveIsSet(RoadRouteStatus status) {
        RoadRouteResult result = RoadRouteResult.builder(status, "osrm").message("No route.").build();
        var withoutStore = new RecordingStore(null);
        var withStore = new RecordingStore(null);

        createProvider(new CountingProvider(result), withoutStore).routeAsync(createRequest()).join();
        createProvider(new CountingProvider(result), withStore, options -> options.setNegativeTimeToLive(Duration.ofDays(1)))
                .routeAsync(createRequest()).join();

        assertThat(withoutStore.writes).isEmpty();
        assertThat(withStore.writes).singleElement().extracting(Write::timeToLive).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void routeAsyncNeverStoresAnUnavailableResult() {
        var store = new RecordingStore(null);
        var inner = new CountingProvider(RoadRouteResult.builder(RoadRouteStatus.UNAVAILABLE, "osrm").message("Timed out.").build());
        var provider = createProvider(inner, store, options -> options.setNegativeTimeToLive(Duration.ofDays(1)));

        provider.routeAsync(createRequest()).join();
        provider.routeAsync(createRequest()).join();

        assertThat(store.writes).isEmpty();
        assertThat(inner.calls()).isEqualTo(2);
    }

    @Test
    void routeAsyncLeavesGeometryOutOfTheStoreWhenAsked() {
        var store = new RecordingStore(null);
        var provider = createProvider(new CountingProvider(success()), store, options -> options.setCacheGeometry(false));

        RoadRouteResult result = provider.routeAsync(createRequest()).join();

        assertThat(result.geometry()).as("the provider's own result is returned unchanged").isNotNull();
        assertThat(store.writes).singleElement().satisfies(write -> assertThat(write.result().geometry()).isNull());
    }

    @Test
    void routeAsyncRoutesWithoutTheCacheWhenTheStoreFails() {
        List<Exception> errors = new CopyOnWriteArrayList<>();
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new FailingStore(), options -> options.setOnCacheError(errors::add));

        RoadRouteResult result = provider.routeAsync(createRequest()).join();

        assertThat(result.status()).isEqualTo(RoadRouteStatus.SUCCESS);
        assertThat(inner.calls()).isEqualTo(1);
        assertThat(errors).as("both the read and the write failed").hasSize(2).allMatch(UncheckedIOException.class::isInstance);
    }

    @Test
    void routeAsyncRoutesWithoutTheCacheWhenTheStoreFailsAsynchronously() {
        List<Exception> errors = new CopyOnWriteArrayList<>();
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new FailingStore(true), options -> options.setOnCacheError(errors::add));

        RoadRouteResult result = provider.routeAsync(createRequest()).join();

        assertThat(result.status()).isEqualTo(RoadRouteStatus.SUCCESS);
        assertThat(inner.calls()).isEqualTo(1);
        assertThat(errors).hasSize(2).allMatch(UncheckedIOException.class::isInstance);
    }

    @Test
    void routeAsyncRoutesAgainWhenTheStoreReturnsAnUnusableResult() {
        List<Exception> errors = new CopyOnWriteArrayList<>();
        var store = new RecordingStore(RoadRouteResult.builder(RoadRouteStatus.UNAVAILABLE, "osrm").build());
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, store, options -> options.setOnCacheError(errors::add));

        RoadRouteResult result = provider.routeAsync(createRequest()).join();

        assertThat(result.status()).isEqualTo(RoadRouteStatus.SUCCESS);
        assertThat(inner.calls()).isEqualTo(1);
        assertThat(errors).singleElement().isInstanceOf(IllegalStateException.class);
    }

    static Stream<RoadRouteResult> unusableSuccesses() {
        return Stream.of(
                RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm").distanceKm(-1.0).build(),
                RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm").distanceKm(Double.NaN).build(),
                RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm").distanceKm(10.0).durationHours(Double.POSITIVE_INFINITY).build(),
                RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm").distanceKm(10.0).geometry(List.of(new Coordinate(0, 0))).build(),
                RoadRouteResult.builder(RoadRouteStatus.SUCCESS, " ").distanceKm(10.0).build(),
                // Java only: the engine rejects a zero duration for a non-zero distance, so a stored one is routed again.
                RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm").distanceKm(10.0).durationHours(0.0).build());
    }

    @ParameterizedTest
    @MethodSource("unusableSuccesses")
    void routeAsyncRoutesAgainWhenAStoredSuccessFailsTheEnginesChecks(RoadRouteResult stored) {
        List<Exception> errors = new CopyOnWriteArrayList<>();
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new RecordingStore(stored), options -> options.setOnCacheError(errors::add));

        RoadRouteResult result = provider.routeAsync(createRequest()).join();

        assertThat(result.distanceKm()).isEqualTo(157.0);
        assertThat(inner.calls()).isEqualTo(1);
        assertThat(errors).singleElement().isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("unusableSuccesses")
    void routeAsyncDoesNotStoreAResultTheEngineWouldReject(RoadRouteResult routed) {
        var store = new RecordingStore(null);
        var provider = createProvider(new CountingProvider(routed), store);

        assertThat(provider.routeAsync(createRequest()).join()).isEqualTo(routed);
        assertThat(store.writes).isEmpty();
    }

    static Stream<RoadRouteResult> usableSuccesses() {
        return Stream.of(
                success(),
                RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm").distanceKm(0.0).durationHours(0.0).build(),
                RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm").distanceKm(10.0).build(),
                RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm").distanceKm(10.0).durationHours(1.0)
                        .geometry(List.of(new Coordinate(-0.19, 51.15), new Coordinate(-0.45, 51.47))).build());
    }

    // isUsable repeats the engine's checks on a provider's success, so run the same results through the engine to keep
    // the two in step.
    @ParameterizedTest
    @MethodSource({"usableSuccesses", "unusableSuccesses"})
    void isUsableAgreesWithTheEngine(RoadRouteResult routed) {
        var request = new MovementRequest(MovementParser.parse("Pickup GBLGW to Airport GBLHR Road"))
                .setRoadRouteProvider(r -> CompletableFuture.completedFuture(routed))
                .setRoadRoutingMode(RoadRoutingMode.REQUIRE_NETWORK);

        boolean accepted;
        try {
            DefaultTradeRouterEngine.getDefault().calculateMovementAsync(request).join();
            accepted = true;
        } catch (CompletionException ex) {
            assertThat(ex).hasCauseInstanceOf(IllegalStateException.class);
            accepted = false;
        }

        assertThat(CachingRoadRouteProvider.isUsable(routed)).isEqualTo(accepted);
    }

    @Test
    void routeAsyncServesAZeroLengthSuccessWithAZeroDuration() {
        RoadRouteResult stored = RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm").distanceKm(0.0).durationHours(0.0).build();
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new RecordingStore(stored));

        assertThat(provider.routeAsync(createRequest()).join()).isEqualTo(stored);
        assertThat(inner.calls()).isZero();
    }

    @Test
    void routeAsyncIgnoresExceptionsFromTheErrorCallback() {
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new FailingStore(), options -> options.setOnCacheError(error -> {
            throw new IllegalStateException("Logger is down.");
        }));

        RoadRouteResult result = provider.routeAsync(createRequest()).join();

        assertThat(result.status()).isEqualTo(RoadRouteStatus.SUCCESS);
        assertThat(inner.calls()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"181.0, -179.0", "180.0, -180.0", "179.999999, -180.0"})
    void routeAsyncSharesAnEntryBetweenEquivalentLongitudes(double first, double second) {
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new DistributedRoadRouteCache(new MemoryCache()));

        provider.routeAsync(createRequest(new Coordinate(0, 0), new Coordinate(first, 10))).join();
        provider.routeAsync(createRequest(new Coordinate(0, 0), new Coordinate(second, 10))).join();

        assertThat(inner.calls()).isEqualTo(1);
    }

    @Test
    void routeAsyncCancelsTheProviderCallWhenTheCallerCancels() {
        var pending = new CompletableFuture<RoadRouteResult>();
        RoadRouteProvider inner = request -> pending;
        var store = new RecordingStore(null);
        var provider = createProvider(inner, store);

        CompletableFuture<RoadRouteResult> result = provider.routeAsync(createRequest());
        result.cancel(true);

        assertThat(pending).isCancelled();
        assertThatThrownBy(result::join).isInstanceOf(CancellationException.class);
        assertThat(store.writes).isEmpty();
    }

    @Test
    void routeAsyncCancelsTheStoreReadWhenTheCallerCancels() {
        var pendingRead = new CompletableFuture<RoadRouteResult>();
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new RoadRouteCache() {
            @Override
            public CompletableFuture<RoadRouteResult> getAsync(RoadRouteCacheKey key) {
                return pendingRead;
            }

            @Override
            public CompletableFuture<Void> setAsync(RoadRouteCacheKey key, RoadRouteResult result, Duration timeToLive) {
                return CompletableFuture.completedFuture(null);
            }
        });

        provider.routeAsync(createRequest()).cancel(true);

        assertThat(pendingRead).isCancelled();
        assertThat(inner.calls()).isZero();
    }

    @Test
    void distributedCachePassesACancellationOnToTheStoreRead() {
        var pendingRead = new CompletableFuture<byte[]>();
        var store = new DistributedRoadRouteCache(new DistributedCache() {
            @Override
            public CompletableFuture<byte[]> getAsync(String key) {
                return pendingRead;
            }

            @Override
            public CompletableFuture<Void> setAsync(String key, byte[] value, Duration timeToLive) {
                return CompletableFuture.completedFuture(null);
            }
        });
        var provider = createProvider(new CountingProvider(success()), store);

        provider.routeAsync(createRequest()).cancel(true);

        assertThat(pendingRead).isCancelled();
    }

    @Test
    void routeAsyncPassesOnAFailureFromTheProvider() {
        RoadRouteProvider inner = request -> CompletableFuture.failedFuture(new IllegalStateException("Provider bug."));
        var provider = createProvider(inner, new RecordingStore(null));

        assertThatThrownBy(() -> provider.routeAsync(createRequest()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Provider bug.");
    }

    @Test
    void routeAsyncReportsANullFutureFromTheStore() {
        List<Exception> errors = new CopyOnWriteArrayList<>();
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new RoadRouteCache() {
            @Override
            public CompletableFuture<RoadRouteResult> getAsync(RoadRouteCacheKey key) {
                return null;
            }

            @Override
            public CompletableFuture<Void> setAsync(RoadRouteCacheKey key, RoadRouteResult result, Duration timeToLive) {
                return null;
            }
        }, options -> options.setOnCacheError(errors::add));

        assertThat(provider.routeAsync(createRequest()).join().status()).isEqualTo(RoadRouteStatus.SUCCESS);
        assertThat(errors).hasSize(2).allMatch(NullPointerException.class::isInstance);
    }

    @ParameterizedTest
    @CsvSource(value = {"'', 30, 5", "osrm|driving, 30, 5", "osrm, 0, 5", "osrm, 30, 11", "osrm, 30, -1"})
    void constructorRejectsInvalidOptions(String partition, int timeToLiveDays, int coordinateDecimals) {
        var options = new RoadRouteCacheOptions(partition, Duration.ofDays(timeToLiveDays)).setCoordinateDecimals(coordinateDecimals);

        assertThatThrownBy(() -> new CachingRoadRouteProvider(new CountingProvider(success()), new RecordingStore(null), options))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsMissingOptions() {
        assertThatThrownBy(() -> new CachingRoadRouteProvider(new CountingProvider(success()), new RecordingStore(null),
                new RoadRouteCacheOptions(null, THIRTY_DAYS))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingRoadRouteProvider(new CountingProvider(success()), new RecordingStore(null),
                new RoadRouteCacheOptions("osrm", null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsANonPositiveNegativeTimeToLive() {
        assertThatThrownBy(() -> new CachingRoadRouteProvider(
                new CountingProvider(success()), new RecordingStore(null), options().setNegativeTimeToLive(Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsATimeToLiveBeyondTheMaximum() {
        assertThatThrownBy(() -> new CachingRoadRouteProvider(
                new CountingProvider(success()), new RecordingStore(null),
                options().setTimeToLive(Duration.ofSeconds(Long.MAX_VALUE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("36,525 days");
        assertThatThrownBy(() -> new CachingRoadRouteProvider(
                new CountingProvider(success()), new RecordingStore(null),
                options().setNegativeTimeToLive(RoadRouteCacheOptions.MAXIMUM_TIME_TO_LIVE.plusDays(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorReadsTheOptionsOnce() {
        var inner = new CountingProvider(success());
        var store = new RecordingStore(null);
        var options = options();
        var provider = createProvider(inner, store, ignored -> options);

        options.setTimeToLive(Duration.ofDays(1));
        provider.routeAsync(createRequest()).join();

        assertThat(store.writes).singleElement().extracting(Write::timeToLive).isEqualTo(THIRTY_DAYS);
    }

    @Test
    void distributedCacheStoresAtTheMaximumTimeToLive() {
        var store = new DistributedRoadRouteCache(new MemoryCache());

        store.setAsync(key(), success(), RoadRouteCacheOptions.MAXIMUM_TIME_TO_LIVE).join();

        assertThat(store.getAsync(key()).join()).isNotNull();
    }

    @Test
    void cacheKeyIsLocaleInvariantAndRounded() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            var key = RoadRouteCacheKey.create(" osrm-driving ",
                    createRequest(new Coordinate(-0.190284, 51.148065), new Coordinate(0.1, -0.000001)), 5);

            assertThat(key.value()).isEqualTo("osrm-driving|-0.19028,51.14807|0.1,0");
            assertThat(key.partition()).isEqualTo("osrm-driving");
            assertThat(key).hasToString(key.value());
        } finally {
            Locale.setDefault(previous);
        }
    }

    // Expected keys were produced by TradeRouter.Net 2.9.0's RoadRouteCacheKey.Create for the same inputs, so Java and
    // .NET services share entries.
    @ParameterizedTest
    @CsvSource(delimiterString = ";", value = {
            "1e-05; 2.675; 2.675; 1e-05; 0; osrm-x|0,3|3,0",
            "540.0; 1.005; 1.005; 0.0; 1; osrm-x|-180,1|1,0",
            "4.9999e-05; 2.675; 2.675; 4.9999e-05; 3; osrm-x|0,2.675|2.675,0",
            "-89.999995; 1.5e-05; 1.5e-05; -89.999995; 5; osrm-x|-90,2E-05|1E-05,-90",
            "-5e-06; -0.190284; -0.190284; -5e-06; 6; osrm-x|-5E-06,-0.190284|-0.190284,-5E-06",
            "359.99999; 0.125; 0.125; 0.0; 7; osrm-x|-1E-05,0.125|0.125,0",
            "-4.9e-06; -5e-06; -5e-06; -4.9e-06; 7; osrm-x|-4.9E-06,-5E-06|-5E-06,-4.9E-06",
            "180.0; 1e-05; 1e-05; 0.0; 8; osrm-x|-180,1E-05|1E-05,0",
            "4.9999e-05; 0.0001; 0.0001; 4.9999e-05; 9; osrm-x|4.9999E-05,0.0001|0.0001,4.9999E-05",
            "1.5e-05; -4.9e-06; -4.9e-06; 1.5e-05; 10; osrm-x|1.5E-05,-4.9E-06|-4.9E-06,1.5E-05",
            "32.37312620773429; -33.02678566333107; -29.70735; 17.869850600850526; 4; osrm-x|32.3731,-33.0268|-29.7074,17.8699",
            "-0.0001538343499762556; 5.856125; -78.2601550000001; -31.518905; 5; osrm-x|-0.00015,5.85613|-78.26016,-31.51891",
            "-129.5064750000001; -80.51433158992806; -135.2800706055509; -88.73027921129186; 5; osrm-x|-129.50648,-80.51433|-135.28007,-88.73028",
            "41.78075600499999; -42.042271128438664; 112.10970770854988; -0.0008287485392076113; 8; osrm-x|41.780756,-42.04227113|112.10970771,-0.00082875"})
    void cacheKeyMatchesTradeRouterNet(
            double fromLongitude, double fromLatitude, double toLongitude, double toLatitude, int decimals, String expected) {
        var request = createRequest(new Coordinate(fromLongitude, fromLatitude), new Coordinate(toLongitude, toLatitude));

        assertThat(RoadRouteCacheKey.create("osrm-x", request, decimals).value()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "0.00001, 5, 1E-05",
            "-0.00001, 5, -1E-05",
            "0.000015, 6, 1.5E-05",
            "0.0001, 5, 0.0001",
            "0.0000000001, 10, 1E-10",
            "51, 5, 51",
            "180, 0, 180",
            "-179.99999, 5, -179.99999",
            "1.0000000001, 10, 1.0000000001"})
    void cacheKeyFormatsNumbersAsDotNetDoes(double value, int decimals, String expected) {
        assertThat(RoadRouteCacheKey.format(RoadRouteCacheKey.roundAwayFromZero(value, decimals), decimals)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0.5, 0, 1", "-0.5, 0, -1", "2.5, 0, 3", "0.125, 2, 0.13", "-0.125, 2, -0.13", "1.005, 2, 1"})
    void cacheKeyRoundsHalfAwayFromZeroAfterScaling(double value, int decimals, double expected) {
        assertThat(RoadRouteCacheKey.roundAwayFromZero(value, decimals)).isEqualTo(expected);
    }

    @Test
    void cacheKeyRejectsAnInvalidCoordinate() {
        assertThatThrownBy(() -> RoadRouteCacheKey.create("osrm",
                createRequest(new Coordinate(0, 91), new Coordinate(1, 1)), 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void distributedCacheRoundTripsEveryField() {
        var store = new DistributedRoadRouteCache(new MemoryCache());
        RoadRouteResult result = RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm")
                .distanceKm(64.321)
                .durationHours(1.05)
                .geometry(List.of(new Coordinate(-0.19028, 51.14806), new Coordinate(-0.3, 51.3), new Coordinate(-0.45, 51.46667)))
                .profile("driving")
                .dataVersion("england-260914")
                .message("note")
                .build();

        store.setAsync(key(), result, THIRTY_DAYS).join();

        assertThat(store.getAsync(key()).join()).isEqualTo(result);
    }

    @Test
    void distributedCacheWritesTheDotNetPayload() {
        assertThat(DistributedRoadRouteCache.write(success())).isEqualTo(
                "{\"v\":1,\"status\":\"Success\",\"distanceKm\":157,\"durationHours\":2,\"geometry\":[0,0,0.5,0.5,1,1],"
                        + "\"source\":\"osrm\",\"profile\":\"driving\"}");
        assertThat(DistributedRoadRouteCache.write(
                RoadRouteResult.builder(RoadRouteStatus.OUTSIDE_COVERAGE, "osrm").message("No match.").build()))
                .isEqualTo("{\"v\":1,\"status\":\"OutsideCoverage\",\"source\":\"osrm\",\"message\":\"No match.\"}");
    }

    @Test
    void distributedCacheWritesAnExpiryUnderThePrefixedKey() {
        var cache = new MemoryCache();
        var store = new DistributedRoadRouteCache(cache, "eDC:");

        store.setAsync(key(), success(), THIRTY_DAYS).join();

        assertThat(cache.lastKey).isEqualTo("eDC:" + key().value());
        assertThat(cache.lastTimeToLive).isEqualTo(THIRTY_DAYS);
    }

    @Test
    void distributedCacheReturnsNullForAMissingEntryOrALaterPayloadVersion() {
        var cache = new MemoryCache();
        var store = new DistributedRoadRouteCache(cache);
        cache.put(DistributedRoadRouteCache.DEFAULT_KEY_PREFIX + key().value(), "{\"v\":2,\"status\":\"Success\",\"source\":\"osrm\"}");

        assertThat(store.getAsync(key()).join()).isNull();
        assertThat(store.getAsync(RoadRouteCacheKey.create("other", createRequest(), 5)).join()).isNull();
    }

    @Test
    void distributedCacheReadsStatusNamesWithoutRegardToCase() {
        var cache = new MemoryCache();
        var store = new DistributedRoadRouteCache(cache);
        cache.put(DistributedRoadRouteCache.DEFAULT_KEY_PREFIX + key().value(), "{\"v\":1,\"status\":\"noroute\",\"source\":\"osrm\"}");

        assertThat(store.getAsync(key()).join().status()).isEqualTo(RoadRouteStatus.NO_ROUTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "null",
            "[1]",
            "{\"status\":\"Success\",\"source\":\"osrm\",\"distanceKm\":1}",
            "{\"v\":\"1\",\"status\":\"Success\",\"source\":\"osrm\"}",
            "{\"v\":1,\"status\":\"Success\"}",
            "{\"v\":1,\"status\":\"Success\",\"source\":\"\"}",
            "{\"v\":1,\"status\":\"Success\",\"source\":\"osrm\",\"geometry\":[0,0,1]}",
            "{\"v\":1,\"status\":\"Success\",\"source\":\"osrm\",\"distanceKm\":\"far\"}",
            "{\"v\":1,\"status\":\"Sideways\",\"source\":\"osrm\"}"})
    void distributedCacheThrowsForAnUnreadableEntry(String payload) {
        var cache = new MemoryCache();
        var store = new DistributedRoadRouteCache(cache);
        cache.put(DistributedRoadRouteCache.DEFAULT_KEY_PREFIX + key().value(), payload);

        assertThatThrownBy(() -> store.getAsync(key()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void movementAsyncUsesTheCacheForRoadLegs() {
        var inner = new CountingProvider(success());
        var provider = createProvider(inner, new DistributedRoadRouteCache(new MemoryCache()));
        UnaryOperator<MovementRequest> configure = request -> request
                .setRoadRouteProvider(provider)
                .setRoadRoutingMode(RoadRoutingMode.REQUIRE_NETWORK);

        LegResult first = DefaultTradeRouterEngine.getDefault().calculateMovementAsync(configure.apply(
                new MovementRequest(MovementParser.parse("Pickup GBLGW to Airport GBLHR Road")))).join().legs().get(0);
        LegResult second = DefaultTradeRouterEngine.getDefault().calculateMovementAsync(configure.apply(
                new MovementRequest(MovementParser.parse("Pickup GBLGW to Airport GBLHR Road")))).join().legs().get(0);

        assertThat(inner.calls()).isEqualTo(1);
        assertThat(second.length()).isEqualTo(first.length());
        assertThat(second.feature().properties().getDistanceSource()).isEqualTo("osrm");
        assertThat(second.feature().properties().getDistanceBasis()).isEqualTo("road_network");
    }

    private static RoadRouteResult success() {
        return RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "osrm")
                .distanceKm(157.0)
                .durationHours(2.0)
                .geometry(List.of(new Coordinate(0, 0), new Coordinate(0.5, 0.5), new Coordinate(1, 1)))
                .profile("driving")
                .build();
    }

    private static RoadRouteCacheOptions options() {
        return new RoadRouteCacheOptions("osrm-driving-test", THIRTY_DAYS);
    }

    private static CachingRoadRouteProvider createProvider(RoadRouteProvider inner, RoadRouteCache store) {
        return createProvider(inner, store, UnaryOperator.identity());
    }

    private static CachingRoadRouteProvider createProvider(
            RoadRouteProvider inner, RoadRouteCache store, UnaryOperator<RoadRouteCacheOptions> configure) {
        return new CachingRoadRouteProvider(inner, store, configure.apply(options()));
    }

    private static RoadRouteRequest createRequest() {
        return createRequest(1);
    }

    private static RoadRouteRequest createRequest(int sequence) {
        return new RoadRouteRequest(sequence,
                new ResolvedLocation("FROM", "From", new Coordinate(0, 0), null, "test"),
                new ResolvedLocation("TO", "To", new Coordinate(1, 1), null, "test"));
    }

    private static RoadRouteRequest createRequest(Coordinate from, Coordinate to) {
        return new RoadRouteRequest(1,
                new ResolvedLocation("FROM", "From", from, null, "test"),
                new ResolvedLocation("TO", "To", to, null, "test"));
    }

    private static RoadRouteCacheKey key() {
        return RoadRouteCacheKey.create("osrm-driving-test", createRequest(), 5);
    }

    private static final class CountingProvider implements RoadRouteProvider {
        private final RoadRouteResult result;
        private final AtomicInteger calls = new AtomicInteger();

        CountingProvider(RoadRouteResult result) {
            this.result = result;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public CompletableFuture<RoadRouteResult> routeAsync(RoadRouteRequest request) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(result);
        }
    }

    private record Write(RoadRouteCacheKey key, RoadRouteResult result, Duration timeToLive) {
    }

    private static final class RecordingStore implements RoadRouteCache {
        private final RoadRouteResult stored;
        private final List<Write> writes = new CopyOnWriteArrayList<>();

        RecordingStore(RoadRouteResult stored) {
            this.stored = stored;
        }

        @Override
        public CompletableFuture<RoadRouteResult> getAsync(RoadRouteCacheKey key) {
            return CompletableFuture.completedFuture(stored);
        }

        @Override
        public CompletableFuture<Void> setAsync(RoadRouteCacheKey key, RoadRouteResult result, Duration timeToLive) {
            writes.add(new Write(key, result, timeToLive));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FailingStore implements RoadRouteCache {
        private final boolean asynchronously;

        FailingStore() {
            this(false);
        }

        FailingStore(boolean asynchronously) {
            this.asynchronously = asynchronously;
        }

        @Override
        public CompletableFuture<RoadRouteResult> getAsync(RoadRouteCacheKey key) {
            return fail();
        }

        @Override
        public CompletableFuture<Void> setAsync(RoadRouteCacheKey key, RoadRouteResult result, Duration timeToLive) {
            return fail();
        }

        private <T> CompletableFuture<T> fail() {
            var error = new UncheckedIOException(new IOException("Store is down."));
            if (asynchronously) {
                return CompletableFuture.failedFuture(error);
            }
            throw error;
        }
    }

    // An in-process DistributedCache for tests that records the last write. Expiry is not modelled.
    private static final class MemoryCache implements DistributedCache {
        private final Map<String, byte[]> entries = new ConcurrentHashMap<>();
        private volatile String lastKey;
        private volatile Duration lastTimeToLive;

        void put(String key, String payload) {
            entries.put(key, payload.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public CompletableFuture<byte[]> getAsync(String key) {
            return CompletableFuture.completedFuture(entries.get(key));
        }

        @Override
        public CompletableFuture<Void> setAsync(String key, byte[] value, Duration timeToLive) {
            lastKey = key;
            lastTimeToLive = timeToLive;
            entries.put(key, value.clone());
            return CompletableFuture.completedFuture(null);
        }
    }
}
