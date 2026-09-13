package io.github.skuirrels.traderouter.geojson;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.json.JsonWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * GeoJSON LineString geometry.
 */
public final class GeoJsonLineString extends GeoJsonGeometry {
    private final List<double[]> coordinates;

    /** Creates a line string from {@code [longitude, latitude]} positions. */
    public GeoJsonLineString(List<double[]> coordinates) {
        this.coordinates = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(coordinates, "coordinates")));
    }

    /** Creates a line string from coordinates without antimeridian handling. */
    public static GeoJsonLineString fromCoordinates(Iterable<Coordinate> coords) {
        List<double[]> result = new ArrayList<>();
        for (Coordinate c : coords) {
            result.add(new double[] {c.longitude(), c.latitude()});
        }
        return new GeoJsonLineString(result);
    }

    @Override
    public String type() {
        return "LineString";
    }

    /** Positions as {@code [longitude, latitude]} arrays. */
    public List<double[]> coordinates() {
        return coordinates;
    }

    @Override
    public List<double[]> positions() {
        return coordinates;
    }

    @Override
    public void writeTo(JsonWriter writer) {
        writer.beginObject();
        writer.name("type").value(type());
        writer.name("coordinates");
        writeLine(writer, coordinates);
        writer.endObject();
    }
}
