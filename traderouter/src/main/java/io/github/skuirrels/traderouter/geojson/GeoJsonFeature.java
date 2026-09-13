package io.github.skuirrels.traderouter.geojson;

import java.util.Objects;

/**
 * A GeoJSON Feature containing route geometry and properties.
 */
public final class GeoJsonFeature {
    private final GeoJsonGeometry geometry;
    private final TradeRouterProperties properties;

    /** Creates a feature with the given geometry (null when no route exists) and properties. */
    public GeoJsonFeature(GeoJsonGeometry geometry, TradeRouterProperties properties) {
        this.geometry = geometry;
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** GeoJSON type: "Feature". */
    public String type() {
        return "Feature";
    }

    /** The route geometry, or null when no route exists under the supplied restrictions. */
    public GeoJsonGeometry geometry() {
        return geometry;
    }

    /** Calculated properties including length, duration, ports and passages. */
    public TradeRouterProperties properties() {
        return properties;
    }

    /** Serializes this feature to a compact GeoJSON string. */
    public String toJson() {
        return toJson(false);
    }

    /** Serializes this feature to a GeoJSON string, optionally indented. */
    public String toJson(boolean writeIndented) {
        return GeoJsonSerializer.serialize(this, writeIndented);
    }

    @Override
    public String toString() {
        return toJson(false);
    }
}
