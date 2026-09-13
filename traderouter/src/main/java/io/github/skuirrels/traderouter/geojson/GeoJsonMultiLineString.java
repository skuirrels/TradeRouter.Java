package io.github.skuirrels.traderouter.geojson;

import io.github.skuirrels.traderouter.json.JsonWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * GeoJSON MultiLineString used when a route is split at the antimeridian.
 */
public final class GeoJsonMultiLineString extends GeoJsonGeometry {
    private final List<List<double[]>> coordinates;

    /** Creates a multi-line string from line segments of {@code [longitude, latitude]} positions. */
    public GeoJsonMultiLineString(List<List<double[]>> coordinates) {
        Objects.requireNonNull(coordinates, "coordinates");
        List<List<double[]>> copy = new ArrayList<>(coordinates.size());
        for (List<double[]> segment : coordinates) {
            copy.add(Collections.unmodifiableList(new ArrayList<>(segment)));
        }
        this.coordinates = Collections.unmodifiableList(copy);
    }

    @Override
    public String type() {
        return "MultiLineString";
    }

    /** Line segments as lists of {@code [longitude, latitude]} positions. */
    public List<List<double[]>> coordinates() {
        return coordinates;
    }

    @Override
    public List<double[]> positions() {
        List<double[]> all = new ArrayList<>();
        for (List<double[]> segment : coordinates) {
            all.addAll(segment);
        }
        return Collections.unmodifiableList(all);
    }

    @Override
    public void writeTo(JsonWriter writer) {
        writer.beginObject();
        writer.name("type").value(type());
        writer.name("coordinates");
        writer.beginArray();
        for (List<double[]> segment : coordinates) {
            writeLine(writer, segment);
        }
        writer.endArray();
        writer.endObject();
    }
}
