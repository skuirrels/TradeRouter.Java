package io.github.skuirrels.traderouter.benchmarks;

import io.github.skuirrels.traderouter.DefaultTradeRouterEngine;
import io.github.skuirrels.traderouter.TradeRouterOptions;
import io.github.skuirrels.traderouter.TradeRoutes;
import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.ports.Port;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * JMH routing benchmarks. Run with {@code java -jar traderouter-benchmarks/target/benchmarks.jar}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RoutingBenchmarks {
    private Coordinate marseille;
    private Coordinate capeTown;
    private Coordinate shanghai;
    private Coordinate rotterdam;
    private Coordinate paris;
    private Coordinate tokyo;
    private TradeRouterOptions dijkstraAppend;
    private TradeRouterOptions astarAppend;
    private TradeRouterOptions withPorts;

    @Setup
    public void setup() {
        marseille = new Coordinate(5.333333, 43.333333);
        capeTown = new Coordinate(18.366667, -33.916667);
        shanghai = new Coordinate(121.47, 31.23);
        rotterdam = new Coordinate(4.48, 51.92);
        paris = new Coordinate(2.333333, 48.866667);
        tokyo = new Coordinate(139.679174, 35.778467);
        dijkstraAppend = new TradeRouterOptions().setAppendOriginDestination(true).setAlgorithm(TradeRouterOptions.DIJKSTRA);
        astarAppend = new TradeRouterOptions().setAppendOriginDestination(true).setAlgorithm(TradeRouterOptions.ASTAR);
        withPorts = new TradeRouterOptions().setAppendOriginDestination(true).setIncludePorts(true);

        // Warm up graph and spatial indexes
        TradeRoutes.calculate(marseille, capeTown);
    }

    @Benchmark
    public GeoJsonFeature dijkstraMarseilleToCapeTown() {
        return TradeRoutes.calculate(marseille, capeTown, dijkstraAppend);
    }

    @Benchmark
    public GeoJsonFeature astarMarseilleToCapeTown() {
        return TradeRoutes.calculate(marseille, capeTown, astarAppend);
    }

    @Benchmark
    public GeoJsonFeature dijkstraShanghaiToRotterdam() {
        return TradeRoutes.calculate(shanghai, rotterdam, dijkstraAppend);
    }

    @Benchmark
    public GeoJsonFeature astarShanghaiToRotterdam() {
        return TradeRoutes.calculate(shanghai, rotterdam, astarAppend);
    }

    @Benchmark
    public GeoJsonFeature routingWithPortsParisToTokyo() {
        return TradeRoutes.calculate(paris, tokyo, withPorts);
    }

    @Benchmark
    public Coordinate kdTreeQueryNearestPort() {
        Port port = DefaultTradeRouterEngine.getDefault().ports().findNearestPort(paris);
        return port == null ? null : port.coordinate();
    }
}
