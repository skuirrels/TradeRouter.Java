package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.common.RouteNormalizer;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.geojson.GeoJsonGeometry;
import io.github.skuirrels.traderouter.geojson.TradeRouterProperties;
import io.github.skuirrels.traderouter.graph.BidirectionalDijkstra;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.graph.PathResult;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.RailRoutingMode;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.SuppliedRoute;
import io.github.skuirrels.traderouter.movements.TransportMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Rail-leg calculation for {@link DefaultTradeRouterEngine}: an imported route, the embedded North American rail
 * network, or great-circle distance with a warning, in that order of precedence.
 */
final class RailLegs {
    private RailLegs() {
    }

    /**
     * Calculates a rail leg from an imported route when one is supplied for the sequence, otherwise on the rail
     * network or at great-circle distance.
     */
    static GeoJsonFeature calculate(
            int sequence,
            ResolvedLocation from,
            ResolvedLocation to,
            DistanceUnit units,
            MovementRequest request,
            Supplier<MaritimeGraph> railGraph) {
        SuppliedRoute supplied = request.getRailRouteOverrides().get(sequence);
        return supplied != null
                ? createSuppliedFeature(from, to, units, request.getSpeedsKmh(), supplied)
                : calculateOnNetwork(from, to, units, request, railGraph);
    }

    /**
     * Routes a rail leg on the embedded North American rail network when both ends lie within the request's snap
     * distance of one connected main line; the gap at each end is added as straight-line distance. Any other rail
     * leg keeps great-circle distance and carries a warning saying why.
     */
    private static GeoJsonFeature calculateOnNetwork(
            ResolvedLocation from,
            ResolvedLocation to,
            DistanceUnit units,
            MovementRequest request,
            Supplier<MaritimeGraph> railGraph) {
        if (request.getRailRoutingMode() == RailRoutingMode.GREAT_CIRCLE_ONLY) {
            return DefaultTradeRouterEngine.calculateStraightLeg(from, to, TransportMode.RAIL, units, request.getSpeedsKmh());
        }

        MaritimeGraph graph = railGraph.get();
        int origin = graph.findNearestNode(from.coordinate());
        int destination = graph.findNearestNode(to.coordinate());
        double accessKm = Haversine.distanceKm(from.coordinate(), graph.getCoordinate(origin));
        double egressKm = Haversine.distanceKm(graph.getCoordinate(destination), to.coordinate());

        String warning;
        if (accessKm > request.getRailNetworkSnapKm() || egressKm > request.getRailNetworkSnapKm()) {
            boolean originFurther = accessKm >= egressKm;
            String label = originFurther ? from.label() : to.label();
            double gapKm = originFurther ? accessKm : egressKm;
            warning = label + " is " + n0(gapKm) + " km from the nearest main line of the embedded North American rail network, "
                    + "beyond the " + n0(request.getRailNetworkSnapKm()) + " km limit; great-circle distance used.";
        } else {
            PathResult path = BidirectionalDijkstra.findPath(graph, origin, destination, null);
            if (!path.path().isEmpty() && Double.isFinite(path.lengthKm())) {
                return createNetworkFeature(graph, from, to, units, request.getSpeedsKmh(),
                        accessKm + path.lengthKm() + egressKm, path.path());
            }
            warning = "No connected main line joins " + from.label() + " and " + to.label()
                    + " on the embedded North American rail network; great-circle distance used.";
        }

        GeoJsonFeature feature = DefaultTradeRouterEngine.calculateStraightLeg(
                from, to, TransportMode.RAIL, units, request.getSpeedsKmh());
        feature.properties().setDistanceWarning(warning);
        return feature;
    }

    /**
     * A rail leg is North American when both ends lie within the request's snap distance of the embedded North
     * American rail network: the coverage test the rail router uses, applied whether the leg was routed on the
     * network, supplied or left at great-circle distance.
     */
    static boolean isNorthAmerican(ResolvedLocation from, ResolvedLocation to, MovementRequest request, MaritimeGraph graph) {
        return Haversine.distanceKm(from.coordinate(), graph.getCoordinate(graph.findNearestNode(from.coordinate())))
                <= request.getRailNetworkSnapKm()
                && Haversine.distanceKm(to.coordinate(), graph.getCoordinate(graph.findNearestNode(to.coordinate())))
                <= request.getRailNetworkSnapKm();
    }

    private static GeoJsonFeature createNetworkFeature(
            MaritimeGraph graph,
            ResolvedLocation from,
            ResolvedLocation to,
            DistanceUnit units,
            Map<TransportMode, Double> speedsKmh,
            double distanceKm,
            List<Coordinate> path) {
        double speedKmh = railSpeedKmh(speedsKmh);

        List<String> railroads = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            String owner = graph.getPassage(path.get(i - 1), path.get(i));
            if (owner != null && !railroads.contains(owner)) {
                railroads.add(owner);
            }
        }

        List<Coordinate> coordinates = new ArrayList<>(path.size() + 2);
        RoadLegs.addIfDifferent(coordinates, from.coordinate());
        for (Coordinate coordinate : path) {
            RoadLegs.addIfDifferent(coordinates, coordinate);
        }
        RoadLegs.addIfDifferent(coordinates, to.coordinate());
        if (coordinates.size() == 1) {
            coordinates.add(to.coordinate());
        }

        double metresToUnits = units.conversionFactorFromMeters();
        TradeRouterProperties properties = new TradeRouterProperties()
                .setLength(distanceKm * 1000.0 * metresToUnits)
                .setUnits(units.unitString())
                .setDurationHours(distanceKm / speedKmh)
                .setDistanceBasis("rail_network")
                .setStraightLineLength(Haversine.distanceKm(from.coordinate(), to.coordinate()) * 1000.0 * metresToUnits)
                .setDistanceSource("narn")
                .setDurationBasis("assumed_speed")
                .setGeometryBasis("rail_network")
                .setRailroads(railroads)
                .setPortOrigin(from.port())
                .setPortDest(to.port());
        return new GeoJsonFeature(GeoJsonGeometry.fromCoordinates(RouteNormalizer.normalizeRoute(coordinates)), properties);
    }

    private static GeoJsonFeature createSuppliedFeature(
            ResolvedLocation from,
            ResolvedLocation to,
            DistanceUnit units,
            Map<TransportMode, Double> speedsKmh,
            SuppliedRoute route) {
        double durationHours = route.durationHours() != null
                ? route.durationHours()
                : route.distanceKm() / railSpeedKmh(speedsKmh);

        double metresToUnits = units.conversionFactorFromMeters();
        List<Coordinate> coordinates = RoadLegs.buildContinuousGeometry(from.coordinate(), to.coordinate(), route.geometry());
        TradeRouterProperties properties = new TradeRouterProperties()
                .setLength(route.distanceKm() * 1000.0 * metresToUnits)
                .setUnits(units.unitString())
                .setDurationHours(durationHours)
                .setDistanceBasis("supplied")
                .setStraightLineLength(Haversine.distanceKm(from.coordinate(), to.coordinate()) * 1000.0 * metresToUnits)
                .setDistanceSource(route.source())
                .setDurationBasis(route.durationHours() != null ? "supplied" : "assumed_speed")
                .setGeometryBasis(route.geometry() != null && route.geometry().size() >= 2 ? "supplied" : "great_circle")
                .setPortOrigin(from.port())
                .setPortDest(to.port());
        return new GeoJsonFeature(GeoJsonGeometry.fromCoordinates(RouteNormalizer.normalizeRoute(coordinates)), properties);
    }

    private static double railSpeedKmh(Map<TransportMode, Double> speedsKmh) {
        Double speedKmh = speedsKmh.get(TransportMode.RAIL);
        if (speedKmh == null || speedKmh <= 0.0) {
            throw new IllegalArgumentException(
                    "No positive speed configured for mode RAIL. Set MovementRequest.getSpeedsKmh() for RAIL.");
        }
        return speedKmh;
    }

    private static String n0(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }
}
