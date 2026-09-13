package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
}
