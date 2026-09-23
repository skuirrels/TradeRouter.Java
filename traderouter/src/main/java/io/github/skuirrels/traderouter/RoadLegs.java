package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.common.RouteNormalizer;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.geojson.GeoJsonGeometry;
import io.github.skuirrels.traderouter.geojson.TradeRouterProperties;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.RoadDistanceEstimate;
import io.github.skuirrels.traderouter.movements.RoadDistanceEstimateRequest;
import io.github.skuirrels.traderouter.movements.RoadDurationMode;
import io.github.skuirrels.traderouter.movements.RoadRouteRequest;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRoutingMode;
import io.github.skuirrels.traderouter.movements.SuppliedRoadRoute;
import io.github.skuirrels.traderouter.movements.TransportMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Road-leg calculation for {@link DefaultTradeRouterEngine}: an imported route, a configured road-network
 * provider, or the labelled fallback estimate, in that order of precedence.
 */
final class RoadLegs {
    private RoadLegs() {
    }

    static CompletableFuture<GeoJsonFeature> calculate(
            int sequence,
            ResolvedLocation from,
            ResolvedLocation to,
            DistanceUnit units,
            MovementRequest request,
            boolean allowProvider,
            AtomicReference<CompletableFuture<?>> inFlight) {
        double straightLineKm = Haversine.distanceKm(from.coordinate(), to.coordinate());

        SuppliedRoadRoute supplied = request.getRoadRouteOverrides().get(sequence);
        if (supplied != null) {
            return CompletableFuture.completedFuture(createFeature(
                    from, to, units, request.getSpeedsKmh(), request.getRoadDurationMode(),
                    supplied.distanceKm(), supplied.durationHours(), supplied.geometry(),
                    "supplied", supplied.source(), null, null, null, straightLineKm));
        }

        if (request.getRoadRoutingMode() == RoadRoutingMode.REQUIRE_NETWORK && request.getRoadRouteProvider() == null) {
            throw new IllegalStateException("Road leg " + sequence + " (" + from.label() + " to " + to.label()
                    + ") requires a road-network provider, but none is configured.");
        }

        if (allowProvider && request.getRoadRouteProvider() != null
                && request.getRoadRoutingMode() != RoadRoutingMode.ESTIMATE_ONLY) {
            CompletableFuture<RoadRouteResult> call = request.getRoadRouteProvider()
                    .routeAsync(new RoadRouteRequest(sequence, from, to));
            if (call == null) {
                throw new IllegalStateException("Road-route provider returned null for leg " + sequence + ".");
            }
            inFlight.set(call);
            return call.thenApply(result -> fromProvider(sequence, from, to, units, request, straightLineKm, result));
        }

        return CompletableFuture.completedFuture(estimate(sequence, from, to, units, request, straightLineKm, null));
    }

    private static GeoJsonFeature fromProvider(
            int sequence,
            ResolvedLocation from,
            ResolvedLocation to,
            DistanceUnit units,
            MovementRequest request,
            double straightLineKm,
            RoadRouteResult result) {
        if (result == null) {
            throw new IllegalStateException("Road-route provider returned null for leg " + sequence + ".");
        }
        if (result.source().isBlank()) {
            throw new IllegalStateException("Road-route provider returned a blank source for leg " + sequence + ".");
        }

        return switch (result.status()) {
            case SUCCESS -> {
                validateProviderSuccess(sequence, result);
                yield createFeature(
                        from, to, units, request.getSpeedsKmh(), request.getRoadDurationMode(),
                        result.distanceKm(), result.durationHours(), result.geometry(),
                        "road_network", result.source(), result.profile(), result.dataVersion(), null, straightLineKm);
            }
            case NO_ROUTE -> throw new IllegalStateException("Road leg " + sequence + " (" + from.label() + " to " + to.label()
                    + ") has no route according to " + result.source() + ": "
                    + (result.message() != null ? result.message() : "no reason supplied"));
            case OUTSIDE_COVERAGE, UNAVAILABLE -> {
                if (request.getRoadRoutingMode() == RoadRoutingMode.REQUIRE_NETWORK) {
                    throw new IllegalStateException("Road leg " + sequence + " (" + from.label() + " to " + to.label()
                            + ") could not be routed by " + result.source() + ": "
                            + (result.message() != null ? result.message() : result.status().displayName()));
                }
                yield estimate(sequence, from, to, units, request, straightLineKm,
                        result.source() + " returned " + result.status().displayName() + ": "
                                + (result.message() != null ? result.message() : "no reason supplied") + ".");
            }
        };
    }

    private static GeoJsonFeature estimate(
            int sequence,
            ResolvedLocation from,
            ResolvedLocation to,
            DistanceUnit units,
            MovementRequest request,
            double straightLineKm,
            String providerWarning) {
        RoadDistanceEstimate estimate = request.getRoadDistanceEstimator()
                .estimate(new RoadDistanceEstimateRequest(sequence, from, to, straightLineKm));
        if (estimate == null) {
            throw new IllegalStateException("Road-distance estimator returned null for leg " + sequence + ".");
        }
        if (estimate.model() == null || estimate.model().isBlank()) {
            throw new IllegalStateException("Road-distance estimator returned a blank model for leg " + sequence + ".");
        }
        if (estimate.warning() == null || estimate.warning().isBlank()) {
            throw new IllegalStateException("Road-distance estimator returned a blank warning for leg " + sequence + ".");
        }
        if (!Double.isFinite(estimate.distanceKm()) || estimate.distanceKm() < straightLineKm) {
            throw new IllegalStateException("Road-distance estimator '" + estimate.model() + "' returned "
                    + estimate.distanceKm() + " km for a " + straightLineKm + " km great-circle lower bound.");
        }

        String warning = providerWarning == null || providerWarning.isBlank()
                ? estimate.warning()
                : providerWarning + " " + estimate.warning();
        return createFeature(
                from, to, units, request.getSpeedsKmh(), request.getRoadDurationMode(),
                estimate.distanceKm(), null, null,
                "circuity_estimate", estimate.model(), null, null, warning, straightLineKm);
    }

    private static GeoJsonFeature createFeature(
            ResolvedLocation from,
            ResolvedLocation to,
            DistanceUnit units,
            Map<TransportMode, Double> speedsKmh,
            RoadDurationMode roadDurationMode,
            double distanceKm,
            Double durationHours,
            List<Coordinate> geometry,
            String distanceBasis,
            String distanceSource,
            String profile,
            String dataVersion,
            String warning,
            double straightLineKm) {
        if (!Double.isFinite(distanceKm) || distanceKm < 0.0) {
            throw new IllegalArgumentException("Road distance must be finite and non-negative. Received: " + distanceKm);
        }
        if (durationHours != null && (!Double.isFinite(durationHours) || durationHours < 0.0)) {
            throw new IllegalArgumentException("Road duration must be finite and non-negative. Received: " + durationHours);
        }
        if (distanceSource == null || distanceSource.isBlank()) {
            throw new IllegalArgumentException("Road distance source cannot be blank.");
        }
        List<Coordinate> coordinates = buildContinuousGeometry(from.coordinate(), to.coordinate(), geometry);
        double length = distanceKm * 1000.0 * units.conversionFactorFromMeters();
        double straightLineLength = straightLineKm * 1000.0 * units.conversionFactorFromMeters();
        boolean useRouteDuration = roadDurationMode == RoadDurationMode.ROUTE_DURATION_WHEN_AVAILABLE && durationHours != null;
        double resolvedDuration;
        if (useRouteDuration) {
            resolvedDuration = durationHours;
        } else {
            Double speedKmh = speedsKmh.get(TransportMode.ROAD);
            if (speedKmh == null || speedKmh <= 0.0) {
                throw new IllegalArgumentException(
                        "No positive speed configured for mode ROAD. Set MovementRequest.getSpeedsKmh() for ROAD.");
            }
            resolvedDuration = distanceKm / speedKmh;
        }

        boolean suppliedBasis = "supplied".equals(distanceBasis);
        TradeRouterProperties properties = new TradeRouterProperties()
                .setLength(length)
                .setUnits(units.unitString())
                .setDurationHours(resolvedDuration)
                .setDistanceBasis(distanceBasis)
                .setStraightLineLength(straightLineLength)
                .setDistanceSource(distanceSource)
                .setRoutingProfile(profile)
                .setRoutingDataVersion(dataVersion)
                .setDurationBasis(useRouteDuration ? (suppliedBasis ? "supplied" : "provider") : "assumed_speed")
                .setGeometryBasis(geometry != null && geometry.size() >= 2
                        ? (suppliedBasis ? "supplied" : "road_network")
                        : "great_circle")
                .setDistanceWarning(warning)
                .setPortOrigin(from.port())
                .setPortDest(to.port());
        return new GeoJsonFeature(GeoJsonGeometry.fromCoordinates(RouteNormalizer.normalizeRoute(coordinates)), properties);
    }

    private static List<Coordinate> buildContinuousGeometry(Coordinate from, Coordinate to, List<Coordinate> routedGeometry) {
        List<Coordinate> coordinates = new ArrayList<>((routedGeometry == null ? 0 : routedGeometry.size()) + 2);
        addIfDifferent(coordinates, from);
        if (routedGeometry != null && routedGeometry.size() >= 2) {
            for (Coordinate coordinate : routedGeometry) {
                addIfDifferent(coordinates, coordinate);
            }
        }
        addIfDifferent(coordinates, to);

        // GeoJSON LineString requires two positions, including for a zero-length road leg.
        if (coordinates.size() == 1) {
            coordinates.add(to);
        }
        return coordinates;
    }

    private static void addIfDifferent(List<Coordinate> coordinates, Coordinate coordinate) {
        coordinate.validate();
        if (coordinates.isEmpty() || !coordinates.get(coordinates.size() - 1).equals(coordinate)) {
            coordinates.add(coordinate);
        }
    }

    private static void validateProviderSuccess(int sequence, RoadRouteResult result) {
        Double distance = result.distanceKm();
        Double duration = result.durationHours();
        if (distance == null || !Double.isFinite(distance) || distance < 0.0) {
            throw new IllegalStateException("Road-route provider returned an invalid distance for leg " + sequence + ".");
        }
        if (duration != null && (!Double.isFinite(duration) || duration < 0.0)) {
            throw new IllegalStateException("Road-route provider returned an invalid duration for leg " + sequence + ".");
        }
        if (distance > 0.0 && duration != null && duration <= 0.0) {
            throw new IllegalStateException(
                    "Road-route provider returned a non-positive duration for non-zero leg " + sequence + ".");
        }
        if (result.geometry() != null && result.geometry().size() == 1) {
            throw new IllegalStateException(
                    "Road-route provider returned fewer than two geometry positions for leg " + sequence + ".");
        }
    }

    static void validateOverrides(MovementRequest request) {
        List<MovementLeg> legs = request.getLegs();
        for (Map.Entry<Integer, SuppliedRoadRoute> entry : request.getRoadRouteOverrides().entrySet()) {
            Integer sequence = entry.getKey();
            SuppliedRoadRoute route = entry.getValue();
            if (sequence == null || sequence < 1 || sequence > legs.size()) {
                throw new IllegalArgumentException(
                        "Road route override sequence " + sequence + " is outside the movement's leg range.");
            }
            if (legs.get(sequence - 1).mode() != TransportMode.ROAD) {
                throw new IllegalArgumentException("Road route override sequence " + sequence + " does not refer to a road leg.");
            }
            if (route == null) {
                throw new IllegalArgumentException("Road route override sequence " + sequence + " is null.");
            }
            if (!Double.isFinite(route.distanceKm()) || route.distanceKm() < 0.0) {
                throw new IllegalArgumentException("Road route override sequence " + sequence + " has an invalid distance.");
            }
            Double duration = route.durationHours();
            if (duration != null && (!Double.isFinite(duration) || duration < 0.0)) {
                throw new IllegalArgumentException("Road route override sequence " + sequence + " has an invalid duration.");
            }
            if (route.distanceKm() > 0.0 && duration != null && duration <= 0.0) {
                throw new IllegalArgumentException("Road route override sequence " + sequence
                        + " has a non-positive duration for a non-zero distance.");
            }
            if (route.source().isBlank()) {
                throw new IllegalArgumentException("Road route override sequence " + sequence + " has a blank source.");
            }
            if (route.geometry() != null && route.geometry().size() == 1) {
                throw new IllegalArgumentException(
                        "Road route override sequence " + sequence + " geometry needs at least two positions.");
            }
            if (route.geometry() != null) {
                for (Coordinate coordinate : route.geometry()) {
                    coordinate.validate();
                }
            }
        }
    }
}
