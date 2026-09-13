package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.ports.PortDatabase;

import java.util.List;

/**
 * Service interface for maritime sea route calculation and network querying. The embedded implementation is
 * {@link DefaultTradeRouterEngine}.
 */
public interface TradeRouterEngine {

    /** The underlying maritime navigation graph. */
    MaritimeGraph graph();

    /** The port database indexed spatially. */
    PortDatabase ports();

    /** Calculates the shortest maritime route between origin and destination coordinates with default options. */
    default GeoJsonFeature calculateRoute(Coordinate origin, Coordinate destination) {
        return calculateRoute(origin, destination, null);
    }

    /** Calculates the shortest maritime route between origin and destination coordinates. */
    GeoJsonFeature calculateRoute(Coordinate origin, Coordinate destination, TradeRouterOptions options);

    /**
     * Calculates maritime route(s) between origin and destination coordinates. When area-based port matrices
     * match multiple ports, multiple features are returned.
     */
    List<GeoJsonFeature> calculateRoutes(Coordinate origin, Coordinate destination, TradeRouterOptions options);

    /** Calculates the shortest maritime route between coordinates given as longitude and latitude. */
    default GeoJsonFeature calculateRoute(double originLon, double originLat, double destLon, double destLat) {
        return calculateRoute(originLon, originLat, destLon, destLat, null);
    }

    /** Calculates the shortest maritime route between coordinates given as longitude and latitude. */
    GeoJsonFeature calculateRoute(double originLon, double originLat, double destLon, double destLat, TradeRouterOptions options);

    /** Calculates the shortest maritime route between two port codes, for example "FRLEH" and "CNTSN". */
    default GeoJsonFeature calculateRoute(String originPortCode, String destPortCode) {
        return calculateRoute(originPortCode, destPortCode, null);
    }

    /** Calculates the shortest maritime route between two port codes, for example "FRLEH" and "CNTSN". */
    GeoJsonFeature calculateRoute(String originPortCode, String destPortCode, TradeRouterOptions options);

    /**
     * Routes a multi-leg movement. Sea legs use the maritime network; road, rail and air legs are straight
     * great-circle lines. Implementations that predate movements keep compiling and throw
     * {@link UnsupportedOperationException}.
     */
    default MovementResult calculateMovement(MovementRequest request) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support multi-leg movements.");
    }

    /**
     * Resolves a UN/LOCODE to a position using the embedded port list and UN/LOCODE list, the same way movement
     * legs are resolved. Throws {@link IllegalArgumentException} when neither list can place the code.
     */
    default ResolvedLocation locate(String code) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support code lookup.");
    }
}
