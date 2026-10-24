package io.github.skuirrels.traderouter.seaareas;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.common.PnPoly;
import io.github.skuirrels.traderouter.data.EmbeddedResources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finds where a route enters and leaves each named sea area. The route is followed along great circles, as its
 * length is measured, and each polygon is read as drawn in longitude and latitude, as Natural Earth draws it.
 * Instances are immutable and safe to share between threads. Callers use {@link SeaArea#traverse(List)}.
 */
final class SeaAreaIndex {
    /**
     * Longest stretch of great circle followed as one straight line in longitude and latitude. A 10 km chord
     * strays from the great circle by metres, far below the accuracy of the 1:50m polygons.
     */
    static final double MAX_CHORD_KM = 10.0;

    /** Side of the square grid cells, in degrees, that index each polygon's boundary segments. */
    private static final double CELL_DEGREES = 1.0;

    private static volatile SeaAreaIndex defaultIndex;

    private final SeaAreaInfo[] areas;
    private final AreaShape[] shapes;

    /**
     * One area to index: its catalogue entry and its rings as flat longitude, latitude pairs.
     *
     * @param info  catalogue entry reported for the area
     * @param rings outer rings and holes alike, each a flat array of longitude, latitude pairs
     */
    record Area(SeaAreaInfo info, List<double[]> rings) {
    }

    /**
     * Creates an index. Each area's rings are flat longitude, latitude pairs; outer rings and holes are told apart
     * by the even-odd rule, so a point is inside when a ray from it crosses the area's rings an odd number of times.
     */
    SeaAreaIndex(List<Area> areas) {
        Objects.requireNonNull(areas, "areas");
        this.areas = new SeaAreaInfo[areas.size()];
        this.shapes = new AreaShape[areas.size()];
        for (int i = 0; i < areas.size(); i++) {
            this.areas[i] = areas.get(i).info();
            this.shapes[i] = new AreaShape(areas.get(i).rings());
        }
    }

    /**
     * Index over the embedded Natural Earth polygons for the catalogue in {@link SeaArea#getCatalogue()}, loaded on
     * first use. A failed load throws its own exception and is retried on the next call.
     */
    static SeaAreaIndex getDefault() {
        SeaAreaIndex result = defaultIndex;
        if (result == null) {
            synchronized (SeaAreaIndex.class) {
                result = defaultIndex;
                if (result == null) {
                    result = load();
                    defaultIndex = result;
                }
            }
        }
        return result;
    }

    private static SeaAreaIndex load() {
        Map<Long, List<double[]>> ringsByNeId = EmbeddedResources.loadSeaAreaPolygons();
        List<Area> areas = new ArrayList<>();
        for (SeaAreaInfo info : SeaArea.getCatalogue()) {
            List<double[]> rings = ringsByNeId.get(info.neId());
            if (rings == null) {
                throw new IllegalStateException(
                        "No polygon for sea area '" + info.id() + "' (ne_id " + info.neId() + ").");
            }
            areas.add(new Area(info, rings));
        }
        return new SeaAreaIndex(areas);
    }

    /**
     * Returns the sea areas along a path, in the order it enters them, with distances in kilometres from its start.
     * The path must be continuous: longitudes may run past ±180 as the route normaliser leaves them.
     */
    List<SeaAreaTraversal> traverse(List<Coordinate> path) {
        Objects.requireNonNull(path, "path");
        Walk walk = new Walk(shapes);
        double[] point = new double[2];
        double startKm = 0.0;
        for (int i = 1; i < path.size(); i++) {
            Coordinate from = path.get(i - 1);
            Coordinate to = path.get(i);
            double segmentKm = Haversine.distance(from, to, DistanceUnit.KM);
            if (segmentKm <= 0.0) {
                continue;
            }

            // Each lane point starts afresh with a point-in-polygon test, so no crossing error carries past it.
            walk.forgetState();
            int chords = Math.max(1, (int) Math.ceil(segmentKm / MAX_CHORD_KM));
            GreatCircleArc arc = new GreatCircleArc(from, to);
            double chordStartLon = from.longitude();
            double chordStartLat = from.latitude();
            for (int k = 1; k <= chords; k++) {
                double chordEndLon;
                double chordEndLat;
                if (k == chords) {
                    chordEndLon = to.longitude();
                    chordEndLat = to.latitude();
                } else {
                    arc.pointAt((double) k / chords, point);
                    chordEndLon = point[0];
                    chordEndLat = point[1];
                }
                walk.chord(
                        chordStartLon, chordStartLat, chordEndLon, chordEndLat,
                        startKm + (segmentKm * (k - 1) / chords),
                        startKm + (segmentKm * k / chords));
                chordStartLon = chordEndLon;
                chordStartLat = chordEndLat;
            }

            startKm += segmentKm;
        }

        walk.finish(startKm);
        List<SeaAreaTraversal> result = new ArrayList<>();
        for (AreaInterval interval : merge(walk.intervals)) {
            SeaAreaInfo info = areas[interval.area()];
            result.add(new SeaAreaTraversal(info.id(), info.name(), info.wikidata(), interval.enterKm(), interval.exitKm()));
        }
        return result;
    }

    /**
     * Joins a return to an area with its previous visit when no other listed area is crossed in between, as when
     * a route passes through the North Atlantic's unlisted Sargasso Sea hole, then orders visits by entry.
     */
    static List<AreaInterval> merge(List<AreaInterval> intervals) {
        Map<Integer, List<AreaInterval>> byArea = new LinkedHashMap<>();
        for (AreaInterval interval : intervals) {
            byArea.computeIfAbsent(interval.area(), area -> new ArrayList<>()).add(interval);
        }

        List<AreaInterval> merged = new ArrayList<>(intervals.size());
        for (List<AreaInterval> visits : byArea.values()) {
            visits.sort(Comparator.comparingDouble(AreaInterval::enterKm));
            AreaInterval current = null;
            for (AreaInterval next : visits) {
                if (current != null && !otherAreaBetween(intervals, current, next)) {
                    current = new AreaInterval(current.area(), current.enterKm(), Math.max(current.exitKm(), next.exitKm()));
                    continue;
                }

                if (current != null) {
                    merged.add(current);
                }
                current = next;
            }

            if (current != null) {
                merged.add(current);
            }
        }

        merged.sort(Comparator.comparingDouble(AreaInterval::enterKm)
                .thenComparingDouble(AreaInterval::exitKm)
                .thenComparingInt(AreaInterval::area));
        return merged;
    }

    private static boolean otherAreaBetween(List<AreaInterval> intervals, AreaInterval previous, AreaInterval next) {
        for (AreaInterval other : intervals) {
            if (other.area() != next.area() && other.enterKm() < next.enterKm() && other.exitKm() > previous.exitKm()) {
                return true;
            }
        }
        return false;
    }

    /** One continuous stay inside one area, in kilometres from the start of the path. */
    record AreaInterval(int area, double enterKm, double exitKm) {
    }

    /** Tracks whether the path is inside each area as it advances chord by chord. */
    private static final class Walk {
        private final AreaShape[] shapes;
        private final boolean[] inside;
        private final boolean[] known;
        private final double[] enteredKm;
        private final DoubleList crossings = new DoubleList();
        private final List<AreaInterval> intervals = new ArrayList<>();
        private double lastX = Double.NaN;

        Walk(AreaShape[] shapes) {
            this.shapes = shapes;
            this.inside = new boolean[shapes.length];
            this.known = new boolean[shapes.length];
            this.enteredKm = new double[shapes.length];
        }

        void forgetState() {
            Arrays.fill(known, false);
        }

        /** Advances along one chord, splitting it where it crosses the antimeridian. */
        void chord(double lon0, double lat0, double lon1, double lat1, double km0, double km1) {
            // Same arithmetic as Coordinate.withNormalizedLongitude, without allocating a coordinate per chord.
            double x0 = ((lon0 % 360.0) + 540.0) % 360.0 - 180.0;
            double x1 = x0 + PnPoly.normalizeLongitudeDelta(lon1 - lon0);
            if (x1 <= 180.0 && x1 >= -180.0) {
                piece(x0, lat0, x1, lat1, km0, km1);
                return;
            }

            double boundary = x1 > 180.0 ? 180.0 : -180.0;
            double fraction = (boundary - x0) / (x1 - x0);
            double latAtBoundary = lat0 + ((lat1 - lat0) * fraction);
            double kmAtBoundary = km0 + ((km1 - km0) * fraction);
            piece(x0, lat0, boundary, latAtBoundary, km0, kmAtBoundary);
            double shift = boundary > 0 ? -360.0 : 360.0;
            piece(boundary + shift, latAtBoundary, x1 + shift, lat1, kmAtBoundary, km1);
        }

        void finish(double km) {
            for (int area = 0; area < shapes.length; area++) {
                setInside(area, false, km);
            }
        }

        private void piece(double x0, double y0, double x1, double y1, double km0, double km1) {
            if (km1 <= km0) {
                return;
            }

            // A piece that does not start where the last one ended has wrapped across the antimeridian, where
            // Natural Earth splits polygons into separate rings, so the carried state no longer applies.
            if (!(Math.abs(x0 - lastX) <= 1e-9)) {
                forgetState();
            }
            lastX = x1;

            for (int area = 0; area < shapes.length; area++) {
                AreaShape shape = shapes[area];
                if (!shape.boundsOverlap(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1))) {
                    setInside(area, false, km0);
                    known[area] = true;
                    continue;
                }

                crossings.clear();
                shape.addCrossings(x0, y0, x1, y1, crossings);
                if (crossings.size() == 0 && known[area]) {
                    continue;
                }

                // Between boundary crossings the state cannot change, so the midpoint of each stretch decides it.
                crossings.add(1.0);
                crossings.sort();
                double start = 0.0;
                for (int i = 0; i < crossings.size(); i++) {
                    double end = crossings.get(i);
                    if (end <= start) {
                        continue;
                    }
                    double middle = (start + end) / 2.0;
                    boolean isInside = shape.contains(x0 + ((x1 - x0) * middle), y0 + ((y1 - y0) * middle));
                    setInside(area, isInside, km0 + ((km1 - km0) * start));
                    start = end;
                }

                known[area] = true;
            }
        }

        private void setInside(int area, boolean isInside, double km) {
            if (inside[area] == isInside) {
                return;
            }
            if (isInside) {
                enteredKm[area] = km;
            } else if (km > enteredKm[area]) {
                intervals.add(new AreaInterval(area, enteredKm[area], km));
            }
            inside[area] = isInside;
        }
    }

    /** Growable list of primitive doubles, reused for every piece's boundary crossings. */
    private static final class DoubleList {
        private double[] values = new double[16];
        private int size;

        void clear() {
            size = 0;
        }

        void add(double value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int size() {
            return size;
        }

        double get(int index) {
            return values[index];
        }

        void sort() {
            Arrays.sort(values, 0, size);
        }
    }

    /** Points along the great circle between two coordinates, by fraction of its length. */
    private static final class GreatCircleArc {
        private final double fromX;
        private final double fromY;
        private final double fromZ;
        private final double toX;
        private final double toY;
        private final double toZ;
        private final double angle;

        GreatCircleArc(Coordinate from, Coordinate to) {
            double fromLon = from.longitude() * Math.PI / 180.0;
            double fromLat = from.latitude() * Math.PI / 180.0;
            double toLon = to.longitude() * Math.PI / 180.0;
            double toLat = to.latitude() * Math.PI / 180.0;
            fromX = Math.cos(fromLat) * Math.cos(fromLon);
            fromY = Math.cos(fromLat) * Math.sin(fromLon);
            fromZ = Math.sin(fromLat);
            toX = Math.cos(toLat) * Math.cos(toLon);
            toY = Math.cos(toLat) * Math.sin(toLon);
            toZ = Math.sin(toLat);
            double dot = Math.clamp((fromX * toX) + (fromY * toY) + (fromZ * toZ), -1.0, 1.0);
            angle = Math.acos(dot);
        }

        /** Writes the longitude and latitude at the given fraction of the arc into {@code point}. */
        void pointAt(double fraction, double[] point) {
            double sinAngle = Math.sin(angle);
            double a = sinAngle > 0 ? Math.sin((1.0 - fraction) * angle) / sinAngle : 1.0 - fraction;
            double b = sinAngle > 0 ? Math.sin(fraction * angle) / sinAngle : fraction;
            double x = (a * fromX) + (b * toX);
            double y = (a * fromY) + (b * toY);
            double z = (a * fromZ) + (b * toZ);
            point[0] = Math.atan2(y, x) * 180.0 / Math.PI;
            point[1] = Math.atan2(z, Math.sqrt((x * x) + (y * y))) * 180.0 / Math.PI;
        }
    }

    /** One area's boundary segments, indexed by a grid of cells covering its bounding box. */
    private static final class AreaShape {
        private final double[] x0;
        private final double[] y0;
        private final double[] x1;
        private final double[] y1;
        private final double minX;
        private final double minY;
        private final double maxX;
        private final double maxY;
        private final double originX;
        private final double originY;
        private final int columns;
        private final int rows;
        private final int[] cellOffsets;
        private final int[] cellSegments;

        AreaShape(List<double[]> rings) {
            Objects.requireNonNull(rings, "rings");
            int total = 0;
            for (double[] ring : rings) {
                if (ring.length < 8 || ring.length % 2 != 0) {
                    throw new IllegalArgumentException("A sea-area ring needs at least four longitude, latitude pairs.");
                }
                total += ring.length / 2;
            }

            double[] sx0 = new double[total];
            double[] sy0 = new double[total];
            double[] sx1 = new double[total];
            double[] sy1 = new double[total];
            int segments = 0;
            double lowX = Double.POSITIVE_INFINITY;
            double lowY = Double.POSITIVE_INFINITY;
            double highX = Double.NEGATIVE_INFINITY;
            double highY = Double.NEGATIVE_INFINITY;
            for (double[] ring : rings) {
                int count = ring.length / 2;
                for (int i = 0; i < count; i++) {
                    // Closing the ring explicitly makes open and GeoJSON-closed rings behave the same.
                    int j = (i + 1) % count;
                    if (ring[2 * i] == ring[2 * j] && ring[(2 * i) + 1] == ring[(2 * j) + 1]) {
                        continue;
                    }
                    sx0[segments] = ring[2 * i];
                    sy0[segments] = ring[(2 * i) + 1];
                    sx1[segments] = ring[2 * j];
                    sy1[segments] = ring[(2 * j) + 1];
                    // Each kept segment's end is the next one's start, so tracking both ends covers every vertex used.
                    lowX = Math.min(lowX, Math.min(sx0[segments], sx1[segments]));
                    lowY = Math.min(lowY, Math.min(sy0[segments], sy1[segments]));
                    highX = Math.max(highX, Math.max(sx0[segments], sx1[segments]));
                    highY = Math.max(highY, Math.max(sy0[segments], sy1[segments]));
                    segments++;
                }
            }

            if (segments == 0) {
                throw new IllegalArgumentException("A sea area needs at least one ring.");
            }

            x0 = Arrays.copyOf(sx0, segments);
            y0 = Arrays.copyOf(sy0, segments);
            x1 = Arrays.copyOf(sx1, segments);
            y1 = Arrays.copyOf(sy1, segments);
            minX = lowX;
            minY = lowY;
            maxX = highX;
            maxY = highY;
            originX = Math.floor(minX / CELL_DEGREES) * CELL_DEGREES;
            originY = Math.floor(minY / CELL_DEGREES) * CELL_DEGREES;
            columns = (int) Math.floor((maxX - originX) / CELL_DEGREES) + 1;
            rows = (int) Math.floor((maxY - originY) / CELL_DEGREES) + 1;

            int[] counts = new int[(columns * rows) + 1];
            forEachSegmentCell((cell, segment) -> counts[cell + 1]++);
            for (int i = 1; i < counts.length; i++) {
                counts[i] += counts[i - 1];
            }
            cellOffsets = counts;
            int[] listed = new int[counts[counts.length - 1]];
            int[] positions = counts.clone();
            forEachSegmentCell((cell, segment) -> listed[positions[cell]++] = segment);
            cellSegments = listed;
        }

        boolean boundsOverlap(double otherMinX, double otherMinY, double otherMaxX, double otherMaxY) {
            return otherMaxX >= minX && otherMinX <= maxX && otherMaxY >= minY && otherMinY <= maxY;
        }

        /** Casts a ray east from the point and counts the boundary segments it crosses. */
        boolean contains(double x, double y) {
            if (x < minX || x > maxX || y < minY || y > maxY) {
                return false;
            }

            int row = row(y);
            boolean inside = false;
            for (int column = column(x); column < columns; column++) {
                int cell = (row * columns) + column;
                for (int i = cellOffsets[cell]; i < cellOffsets[cell + 1]; i++) {
                    int s = cellSegments[i];
                    double sy0 = y0[s];
                    double sy1 = y1[s];
                    if ((sy0 > y) == (sy1 > y)) {
                        continue;
                    }
                    double crossingX = x0[s] + ((y - sy0) * (x1[s] - x0[s]) / (sy1 - sy0));
                    // A segment is listed in every cell it touches; count it only in the cell where the ray meets it.
                    if (crossingX > x && column(crossingX) == column) {
                        inside = !inside;
                    }
                }
            }

            return inside;
        }

        /** Adds the fractions along the line from (ax, ay) to (bx, by) at which it meets the boundary. */
        void addCrossings(double ax, double ay, double bx, double by, DoubleList crossings) {
            double dx = bx - ax;
            double dy = by - ay;
            int firstColumn = column(Math.min(ax, bx));
            int lastColumn = column(Math.max(ax, bx));
            int firstRow = row(Math.min(ay, by));
            int lastRow = row(Math.max(ay, by));
            for (int row = firstRow; row <= lastRow; row++) {
                for (int column = firstColumn; column <= lastColumn; column++) {
                    int cell = (row * columns) + column;
                    for (int i = cellOffsets[cell]; i < cellOffsets[cell + 1]; i++) {
                        // Repeats from segments listed in several cells only add empty stretches, which are skipped.
                        int s = cellSegments[i];
                        double ex = x1[s] - x0[s];
                        double ey = y1[s] - y0[s];
                        double denominator = (dx * ey) - (dy * ex);
                        if (denominator == 0.0) {
                            continue;
                        }
                        double qx = x0[s] - ax;
                        double qy = y0[s] - ay;
                        double along = ((qx * ey) - (qy * ex)) / denominator;
                        double onSegment = ((qx * dy) - (qy * dx)) / denominator;
                        if (along >= 0.0 && along <= 1.0 && onSegment >= 0.0 && onSegment <= 1.0) {
                            crossings.add(along);
                        }
                    }
                }
            }
        }

        private int column(double x) {
            return Math.clamp((long) Math.floor((x - originX) / CELL_DEGREES), 0, columns - 1);
        }

        private int row(double y) {
            return Math.clamp((long) Math.floor((y - originY) / CELL_DEGREES), 0, rows - 1);
        }

        private void forEachSegmentCell(CellVisitor visit) {
            for (int s = 0; s < x0.length; s++) {
                int firstColumn = column(Math.min(x0[s], x1[s]));
                int lastColumn = column(Math.max(x0[s], x1[s]));
                int firstRow = row(Math.min(y0[s], y1[s]));
                int lastRow = row(Math.max(y0[s], y1[s]));
                for (int row = firstRow; row <= lastRow; row++) {
                    for (int column = firstColumn; column <= lastColumn; column++) {
                        visit.visit((row * columns) + column, s);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface CellVisitor {
        void visit(int cell, int segment);
    }
}
