package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.RoadRouteProvider;
import io.github.skuirrels.traderouter.movements.RoadRouteRequest;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRouteStatus;
import io.github.skuirrels.traderouter.passages.Passage;
import io.github.skuirrels.traderouter.ports.PortDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConcurrencyTests {

    @Test
    void concurrentRoutingParallelQueriesShouldAllSucceedConsistently() throws Exception {
        record Pair(Coordinate origin, Coordinate dest) {
        }
        List<Pair> testPairs = List.of(
                new Pair(new Coordinate(5.333333, 43.333333), new Coordinate(18.366667, -33.916667)),
                new Pair(new Coordinate(121.47, 31.23), new Coordinate(4.48, 51.92)),
                new Pair(new Coordinate(139.64, 35.44), new Coordinate(-118.24, 33.74)),
                new Pair(new Coordinate(52.99, 25.01), new Coordinate(-61.87, 17.15)),
                new Pair(new Coordinate(-74.0, 40.7), new Coordinate(0.0, 51.5)));

        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        Queue<Double> lengths = new ConcurrentLinkedQueue<>();

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                Pair pair = testPairs.get(i % testPairs.size());
                futures.add(executor.submit(() -> {
                    try {
                        GeoJsonFeature route = TradeRoutes.calculate(pair.origin(), pair.dest(),
                                new TradeRouterOptions().setAppendOriginDestination(true));
                        lengths.add(route.properties().getLength());
                    } catch (Throwable ex) {
                        errors.add(ex);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.MINUTES);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(errors).isEmpty();
        assertThat(lengths).hasSize(100);
        assertThat(lengths).allSatisfy(len -> assertThat(len).isGreaterThan(1000.0));
    }

    private static final Coordinate[][] ROUTE_PAIRS = {
            {new Coordinate(5.333333, 43.333333), new Coordinate(18.366667, -33.916667)},
            {new Coordinate(121.47, 31.23), new Coordinate(4.48, 51.92)},
            {new Coordinate(139.64, 35.44), new Coordinate(-118.24, 33.74)},
            {new Coordinate(52.99, 25.01), new Coordinate(-61.87, 17.15)},
            {new Coordinate(-74.0, 40.7), new Coordinate(0.0, 51.5)}
    };

    @ParameterizedTest
    @ValueSource(strings = {"dijkstra", "astar"})
    void concurrentRoutingMatchesSequentialResultsExactly(String algorithm) throws Exception {
        TradeRouterOptions options = new TradeRouterOptions()
                .setAlgorithm(algorithm)
                .setRestrictions(Passage.SUEZ)
                .setReturnPassages(true);
        String[] expected = new String[ROUTE_PAIRS.length];
        for (int i = 0; i < ROUTE_PAIRS.length; i++) {
            expected[i] = fingerprint(TradeRoutes.calculate(ROUTE_PAIRS[i][0], ROUTE_PAIRS[i][1], options));
        }
        String[] actual = new String[200];

        parallelFor(actual.length, Runtime.getRuntime().availableProcessors(), i -> {
            Coordinate[] pair = ROUTE_PAIRS[i % ROUTE_PAIRS.length];
            actual[i] = fingerprint(TradeRoutes.calculate(pair[0], pair[1], options));
        });

        for (int i = 0; i < actual.length; i++) {
            assertThat(actual[i])
                    .as("parallel query %d must match its sequential result", i)
                    .isEqualTo(expected[i % ROUTE_PAIRS.length]);
        }
    }

    @Test
    void concurrentRoutingInterleavedGraphSizesOnSharedThreadsStayCorrect() throws Exception {
        // Per-thread search buffers are sized for the largest graph seen; a small graph must not read stale entries.
        MaritimeGraph smallGraph = new MaritimeGraph();
        for (int lon = 0; lon < 4; lon++) {
            smallGraph.addEdge(new Coordinate(lon, 0), new Coordinate(lon + 1, 0));
        }
        smallGraph.buildIndex();
        DefaultTradeRouterEngine smallEngine = new DefaultTradeRouterEngine(smallGraph, new PortDatabase(List.of()));
        double expectedSmall = smallEngine.calculateRoute(new Coordinate(0, 0), new Coordinate(4, 0)).properties().getLength();
        double expectedLarge = TradeRoutes.calculate(ROUTE_PAIRS[0][0], ROUTE_PAIRS[0][1]).properties().getLength();
        Queue<String> errors = new ConcurrentLinkedQueue<>();

        parallelFor(200, Runtime.getRuntime().availableProcessors(), i -> {
            if (i % 2 == 0) {
                double length = smallEngine.calculateRoute(new Coordinate(0, 0), new Coordinate(4, 0)).properties().getLength();
                if (length != expectedSmall) {
                    errors.add("small graph query " + i + " returned " + length);
                }
            } else {
                double length = TradeRoutes.calculate(ROUTE_PAIRS[0][0], ROUTE_PAIRS[0][1]).properties().getLength();
                if (length != expectedLarge) {
                    errors.add("large graph query " + i + " returned " + length);
                }
            }
        });

        assertThat(errors).isEmpty();
        assertThat(expectedSmall).isCloseTo(Haversine.distanceKm(new Coordinate(0, 0), new Coordinate(4, 0)), within(1e-6));
    }

    @Test
    void concurrentFirstUseBuildsLazyGraphAndPortsOnce() throws Exception {
        AtomicInteger graphBuilds = new AtomicInteger();
        AtomicInteger portBuilds = new AtomicInteger();
        DefaultTradeRouterEngine engine = new DefaultTradeRouterEngine(
                () -> {
                    graphBuilds.incrementAndGet();
                    MaritimeGraph graph = new MaritimeGraph();
                    graph.addEdge(new Coordinate(0, 0), new Coordinate(1, 0));
                    graph.buildIndex();
                    return graph;
                },
                () -> {
                    portBuilds.incrementAndGet();
                    return new PortDatabase(List.of());
                });
        int participants = Math.min(Runtime.getRuntime().availableProcessors(), 8);
        CyclicBarrier start = new CyclicBarrier(participants);

        parallelFor(participants, participants, i -> {
            try {
                start.await(1, TimeUnit.MINUTES);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            engine.calculateRoute(new Coordinate(0, 0), new Coordinate(1, 0), new TradeRouterOptions().setIncludePorts(true));
        });

        assertThat(graphBuilds.get()).isEqualTo(1);
        assertThat(portBuilds.get()).isEqualTo(1);
    }

    @Test
    void concurrentMovementsMatchSequentialResults() {
        String legs = """
                Pickup GBLGW to Port GBFXT Road
                Port GBFXT to Port SGSIN Sea
                """;
        MovementResult expected = TradeRoutes.calculateMovement(legs);
        FixedRoadProvider provider = new FixedRoadProvider();

        List<CompletableFuture<MovementResult>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            boolean usesProvider = i % 2 == 1;
            futures.add(CompletableFuture.supplyAsync(() -> {
                MovementRequest request = new MovementRequest(MovementParser.parse(legs));
                if (usesProvider) {
                    request.setRoadRouteProvider(provider);
                }
                return request;
            }).thenCompose(request -> DefaultTradeRouterEngine.getDefault().calculateMovementAsync(request)));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        for (int i = 0; i < futures.size(); i++) {
            boolean usesProvider = i % 2 == 1;
            MovementResult result = futures.get(i).join();
            assertThat(result.legs().get(1).length()).isEqualTo(expected.legs().get(1).length());
            assertThat(result.legs().get(1).durationHours()).isEqualTo(expected.legs().get(1).durationHours());
            if (usesProvider) {
                assertThat(result.legs().get(0).length()).isEqualTo(FixedRoadProvider.DISTANCE_KM);
            } else {
                assertThat(result.legs().get(0).length()).isEqualTo(expected.legs().get(0).length());
            }
        }
        assertThat(provider.calls())
                .as("half of the 40 movements use the provider for their single road leg")
                .isEqualTo(20);
    }

    private static String fingerprint(GeoJsonFeature route) {
        List<String> passages = route.properties().getTraversedPassages();
        return Double.toString(route.properties().getLength())
                + "|" + Double.toString(route.properties().getDurationHours())
                + "|" + route.geometry().positions().size()
                + "|" + (passages == null ? "" : String.join(",", passages));
    }

    /** Runs {@code body} for 0 to {@code count - 1} on a fixed pool, like .NET Parallel.For, and rethrows the first failure. */
    private static void parallelFor(int count, int threads, IntConsumer body) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> body.accept(index)));
            }
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.MINUTES);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class FixedRoadProvider implements RoadRouteProvider {
        static final double DISTANCE_KM = 42.0;
        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return calls.get();
        }

        @Override
        public CompletableFuture<RoadRouteResult> routeAsync(RoadRouteRequest request) {
            calls.incrementAndGet();
            // Completes on another thread, like the .NET provider's Task.Yield.
            return CompletableFuture.supplyAsync(() -> RoadRouteResult.builder(RoadRouteStatus.SUCCESS, "fixed")
                    .distanceKm(DISTANCE_KM)
                    .durationHours(0.7)
                    .build());
        }
    }
}
