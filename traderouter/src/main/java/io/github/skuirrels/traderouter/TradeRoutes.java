package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.movements.LocationResolver;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Static entry point for calculating maritime sea routes between any two points on Earth. Wraps
 * {@link DefaultTradeRouterEngine#getDefault()} unless another engine is installed with {@link #setEngine}.
 */
public final class TradeRoutes {
    private static volatile TradeRouterEngine customEngine;

    private TradeRoutes() {
    }

    /** The active sea route engine. Defaults to {@link DefaultTradeRouterEngine#getDefault()}. */
    public static TradeRouterEngine getEngine() {
        TradeRouterEngine engine = customEngine;
        return engine != null ? engine : DefaultTradeRouterEngine.getDefault();
    }

    /** Replaces the active engine; pass null to restore the default. */
    public static void setEngine(TradeRouterEngine engine) {
        customEngine = engine;
    }

    /** Resets the engine back to the default singleton instance. */
    public static void resetEngine() {
        customEngine = null;
    }

    /** Calculates the shortest sea route between two coordinates using default options. */
    public static GeoJsonFeature calculate(Coordinate origin, Coordinate destination) {
        return getEngine().calculateRoute(origin, destination, null);
    }

    /** Calculates the shortest sea route between two coordinates using the specified options. */
    public static GeoJsonFeature calculate(Coordinate origin, Coordinate destination, TradeRouterOptions options) {
        return getEngine().calculateRoute(origin, destination, options);
    }

    /** Calculates the shortest sea route between two coordinates specified as longitude and latitude. */
    public static GeoJsonFeature calculate(double originLon, double originLat, double destLon, double destLat) {
        return calculate(originLon, originLat, destLon, destLat, null);
    }

    /** Calculates the shortest sea route between two coordinates specified as longitude and latitude. */
    public static GeoJsonFeature calculate(
            double originLon, double originLat, double destLon, double destLat, TradeRouterOptions options) {
        return getEngine().calculateRoute(new Coordinate(originLon, originLat), new Coordinate(destLon, destLat), options);
    }

    /** Calculates the shortest sea route between two ports identified by their UN/LOCODE or port code. */
    public static GeoJsonFeature calculate(String originPortCode, String destPortCode) {
        return getEngine().calculateRoute(originPortCode, destPortCode, null);
    }

    /** Calculates the shortest sea route between two ports identified by their UN/LOCODE or port code. */
    public static GeoJsonFeature calculate(String originPortCode, String destPortCode, TradeRouterOptions options) {
        return getEngine().calculateRoute(originPortCode, destPortCode, options);
    }

    /** Calculates sea routes returning multiple features if area matrices match multiple port combinations. */
    public static List<GeoJsonFeature> calculateRoutes(Coordinate origin, Coordinate destination) {
        return getEngine().calculateRoutes(origin, destination, null);
    }

    /** Calculates sea routes returning multiple features if area matrices match multiple port combinations. */
    public static List<GeoJsonFeature> calculateRoutes(Coordinate origin, Coordinate destination, TradeRouterOptions options) {
        return getEngine().calculateRoutes(origin, destination, options);
    }

    /**
     * Routes a multi-leg movement described as one leg per line, for example
     * {@code Pickup GBLGW to Port GBFXT Road}. Codes not in the embedded lists need a coordinate; use the
     * overload taking coordinates or build a {@link MovementRequest}.
     */
    public static MovementResult calculateMovement(String legsText) {
        return calculateMovement(legsText, null, null, null, null, null);
    }

    /** Routes a leg-line movement with the given sea options. */
    public static MovementResult calculateMovement(String legsText, TradeRouterOptions seaOptions) {
        return calculateMovement(legsText, null, seaOptions, null, null, null);
    }

    /** Routes a leg-line movement with the given sea options and cargo, either of which may be null. */
    public static MovementResult calculateMovement(
            String legsText, TradeRouterOptions seaOptions, Double cargoTonnes, Double cargoTeu) {
        return calculateMovement(legsText, null, seaOptions, null, cargoTonnes, cargoTeu);
    }

    /**
     * Routes a leg-line movement.
     *
     * @param legsText    one leg per line
     * @param coordinates coordinates for codes neither embedded list can place, keyed by code; may be null
     * @param seaOptions  options applied to every sea leg; may be null
     * @param resolver    resolver consulted last for unknown codes; may be null
     * @param cargoTonnes gross cargo weight in tonnes; may be null
     * @param cargoTeu    container count in TEU; may be null
     */
    public static MovementResult calculateMovement(
            String legsText,
            Map<String, Coordinate> coordinates,
            TradeRouterOptions seaOptions,
            LocationResolver resolver,
            Double cargoTonnes,
            Double cargoTeu) {
        return calculateMovement(MovementParser.parse(legsText), coordinates, seaOptions, resolver, cargoTonnes, cargoTeu);
    }

    /** Routes a strongly typed, continuous movement plan. */
    public static MovementResult calculateMovement(MovementPlan plan) {
        return calculateMovement(plan, null, null, null, null, null);
    }

    /** Routes a movement plan with the given sea options. */
    public static MovementResult calculateMovement(MovementPlan plan, TradeRouterOptions seaOptions) {
        return calculateMovement(plan, null, seaOptions, null, null, null);
    }

    /** Routes a movement plan with the given sea options and cargo, either of which may be null. */
    public static MovementResult calculateMovement(
            MovementPlan plan, TradeRouterOptions seaOptions, Double cargoTonnes, Double cargoTeu) {
        return calculateMovement(plan, null, seaOptions, null, cargoTonnes, cargoTeu);
    }

    /**
     * Routes a movement plan.
     *
     * @param plan        the plan
     * @param coordinates coordinates for codes neither embedded list can place, keyed by code; may be null
     * @param seaOptions  options applied to every sea leg; may be null
     * @param resolver    resolver consulted last for unknown codes; may be null
     * @param cargoTonnes gross cargo weight in tonnes; may be null
     * @param cargoTeu    container count in TEU; may be null
     */
    public static MovementResult calculateMovement(
            MovementPlan plan,
            Map<String, Coordinate> coordinates,
            TradeRouterOptions seaOptions,
            LocationResolver resolver,
            Double cargoTonnes,
            Double cargoTeu) {
        Objects.requireNonNull(plan, "plan");
        return calculateMovement(plan.legs(), coordinates, seaOptions, resolver, cargoTonnes, cargoTeu);
    }

    /** Routes a multi-leg movement request. */
    public static MovementResult calculateMovement(MovementRequest request) {
        return getEngine().calculateMovement(request);
    }

    /** Routes a multi-leg movement asynchronously, allowing a configured road-network provider to be called. */
    public static CompletableFuture<MovementResult> calculateMovementAsync(MovementRequest request) {
        return getEngine().calculateMovementAsync(request);
    }

    /** Resolves a UN/LOCODE to a position from the embedded port list and UN/LOCODE list. */
    public static ResolvedLocation locate(String code) {
        return getEngine().locate(code);
    }

    private static MovementResult calculateMovement(
            List<MovementLeg> legs,
            Map<String, Coordinate> coordinates,
            TradeRouterOptions seaOptions,
            LocationResolver resolver,
            Double cargoTonnes,
            Double cargoTeu) {
        MovementRequest request = new MovementRequest(legs)
                .setCargoTonnes(cargoTonnes)
                .setCargoTeu(cargoTeu)
                .setSeaOptions(seaOptions)
                .setResolver(resolver);
        if (coordinates != null) {
            request.getCoordinates().putAll(coordinates);
        }
        return getEngine().calculateMovement(request);
    }
}
