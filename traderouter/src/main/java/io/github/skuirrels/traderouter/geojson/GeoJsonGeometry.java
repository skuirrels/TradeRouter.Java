package io.github.skuirrels.traderouter.geojson;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.json.JsonParser;
import io.github.skuirrels.traderouter.json.JsonWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Base type for route geometries emitted as RFC 7946 LineString or MultiLineString values.
 */
public abstract class GeoJsonGeometry {

    GeoJsonGeometry() {
    }

    /** GeoJSON geometry type: "LineString" or "MultiLineString". */
    public abstract String type();

    /**
     * All positions in traversal order as {@code [longitude, latitude]} arrays, flattened across
     * antimeridian-split segments. Use {@link GeoJsonMultiLineString#coordinates()} when segment boundaries are
     * required.
     */
    public abstract List<double[]> positions();

    /** Writes the geometry object to a JSON writer. */
    public abstract void writeTo(JsonWriter writer);

    /** Serializes the geometry to a GeoJSON geometry object. */
    public String toJson() {
        return toJson(false);
    }

    /** Serializes the geometry to a GeoJSON geometry object, optionally indented. */
    public String toJson(boolean writeIndented) {
        JsonWriter writer = new JsonWriter(writeIndented);
        writeTo(writer);
        return writer.toString();
    }

    @Override
    public String toString() {
        return toJson(false);
    }

    /**
     * Creates an RFC 7946 geometry, splitting a route at the antimeridian when necessary. Returns null for an
     * empty coordinate list.
     */
    public static GeoJsonGeometry fromCoordinates(List<Coordinate> coordinates) {
        Objects.requireNonNull(coordinates, "coordinates");
        if (coordinates.isEmpty()) {
            return null;
        }
        if (coordinates.size() < 2) {
            throw new IllegalArgumentException("A route geometry requires at least two positions.");
        }

        List<List<double[]>> segments = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        current.add(toPosition(coordinates.get(0)));

        for (int i = 1; i < coordinates.size(); i++) {
            Coordinate previous = coordinates.get(i - 1);
            Coordinate next = coordinates.get(i);
            previous.validate();
            next.validate();

            double[] previousPosition = toPosition(previous);
            double[] nextPosition = toPosition(next);
            double longitudeDelta = nextPosition[0] - previousPosition[0];
            if (Math.abs(Math.abs(longitudeDelta) - 360.0) < 1e-12) {
                current.add(new double[] {previousPosition[0], nextPosition[1]});
                continue;
            }

            if (Math.abs(longitudeDelta) > 180.0) {
                boolean eastbound = longitudeDelta < 0.0;
                double boundary = eastbound ? 180.0 : -180.0;
                double adjustedNextLongitude = nextPosition[0] + (eastbound ? 360.0 : -360.0);
                double ratio = (boundary - previousPosition[0]) / (adjustedNextLongitude - previousPosition[0]);
                double latitude = previous.latitude() + (ratio * (next.latitude() - previous.latitude()));

                if (!positionsEqual(current.get(current.size() - 1), boundary, latitude)) {
                    current.add(new double[] {boundary, latitude});
                }
                if (current.size() >= 2) {
                    segments.add(current);
                }

                current = new ArrayList<>();
                current.add(new double[] {-boundary, latitude});

                if (!positionsEqual(current.get(current.size() - 1), nextPosition[0], nextPosition[1])) {
                    current.add(nextPosition);
                }
            } else {
                // Preserve the two positions required by GeoJSON for a zero-length two-point route,
                // but do not retain duplicate lane vertices in longer routes.
                if (!positionsEqual(current.get(current.size() - 1), nextPosition[0], nextPosition[1])
                        || (coordinates.size() == 2 && i == 1)) {
                    current.add(nextPosition);
                }
            }
        }

        if (current.size() >= 2) {
            segments.add(current);
        }

        if (segments.isEmpty()) {
            throw new IllegalArgumentException("A route geometry requires at least two distinct positions.");
        }

        return segments.size() == 1
                ? new GeoJsonLineString(segments.get(0))
                : new GeoJsonMultiLineString(segments);
    }

    /** Parses a GeoJSON geometry object, returning null for JSON null. */
    public static GeoJsonGeometry fromJson(String json) {
        return fromJsonValue(JsonParser.parse(json));
    }

    static GeoJsonGeometry fromJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> root = JsonParser.asObject(value);
        String type = JsonParser.asString(root.get("type"));
        if (type == null) {
            throw new IllegalArgumentException("GeoJSON geometry type is missing.");
        }
        Object coordinates = root.get("coordinates");
        return switch (type) {
            case "LineString" -> new GeoJsonLineString(readLine(coordinates));
            case "MultiLineString" -> {
                List<List<double[]>> lines = new ArrayList<>();
                for (Object line : JsonParser.asArray(coordinates)) {
                    lines.add(readLine(line));
                }
                yield new GeoJsonMultiLineString(lines);
            }
            default -> throw new IllegalArgumentException("Unsupported route geometry type '" + type + "'.");
        };
    }

    private static List<double[]> readLine(Object element) {
        List<double[]> line = new ArrayList<>();
        for (Object position : JsonParser.asArray(element)) {
            List<Object> ordinates = JsonParser.asArray(position);
            double[] values = new double[ordinates.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = JsonParser.asDouble(ordinates.get(i));
            }
            line.add(values);
        }
        return line;
    }

    static void writeLine(JsonWriter writer, List<double[]> line) {
        writer.beginArray();
        for (double[] position : line) {
            writer.position(position);
        }
        writer.endArray();
    }

    private static double[] toPosition(Coordinate coordinate) {
        coordinate.validate();
        double longitude = coordinate.withNormalizedLongitude().longitude();
        if (longitude == -180.0 && coordinate.longitude() > 0.0) {
            longitude = 180.0;
        }
        return new double[] {longitude, coordinate.latitude()};
    }

    private static boolean positionsEqual(double[] position, double longitude, double latitude) {
        return position[0] == longitude && position[1] == latitude;
    }
}
