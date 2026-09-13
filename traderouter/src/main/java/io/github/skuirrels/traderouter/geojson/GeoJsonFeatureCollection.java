package io.github.skuirrels.traderouter.geojson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * GeoJSON FeatureCollection, used for multi-leg movements.
 */
public final class GeoJsonFeatureCollection {
    private final List<GeoJsonFeature> features;
    private final MovementProperties properties;

    /** Creates a collection of the given features with optional movement totals. */
    public GeoJsonFeatureCollection(List<GeoJsonFeature> features, MovementProperties properties) {
        this.features = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(features, "features")));
        this.properties = properties;
    }

    /** GeoJSON type: "FeatureCollection". */
    public String type() {
        return "FeatureCollection";
    }

    /** Member features, one per leg. */
    public List<GeoJsonFeature> features() {
        return features;
    }

    /** Movement totals, written as a foreign member permitted by RFC 7946 section 6.1, or null. */
    public MovementProperties properties() {
        return properties;
    }

    /** Serializes this collection to a compact GeoJSON string. */
    public String toJson() {
        return toJson(false);
    }

    /** Serializes this collection to a GeoJSON string, optionally indented. */
    public String toJson(boolean writeIndented) {
        return GeoJsonSerializer.serialize(this, writeIndented);
    }

    @Override
    public String toString() {
        return toJson(false);
    }
}
