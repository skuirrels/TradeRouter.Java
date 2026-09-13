package io.github.skuirrels.traderouter.common;

import java.util.List;

/**
 * Point-in-polygon algorithm based on W. Randolph Franklin's PNPOLY.
 */
public final class PnPoly {
    private PnPoly() {
    }

    /**
     * Determines whether a coordinate is inside a polygon defined by its vertices.
     *
     * @param vertices  polygon vertex coordinates (outer ring)
     * @param testPoint the point to test
     * @return true when the point is inside the polygon
     */
    public static boolean containsPoint(List<Coordinate> vertices, Coordinate testPoint) {
        if (vertices == null || vertices.size() < 3) {
            return false;
        }

        int nvert = vertices.size();
        testPoint.validate();
        double[] longitudes = unwrapLongitudes(vertices);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double longitude : longitudes) {
            min = Math.min(min, longitude);
            max = Math.max(max, longitude);
        }
        double centre = (min + max) / 2.0;
        double testx = testPoint.longitude() + (360.0 * Math.rint((centre - testPoint.longitude()) / 360.0));
        double testy = testPoint.latitude();
        boolean inside = false;

        for (int i = 0, j = nvert - 1; i < nvert; j = i++) {
            double viY = vertices.get(i).latitude();
            double vjY = vertices.get(j).latitude();
            double viX = longitudes[i];
            double vjX = longitudes[j];

            if (((viY > testy) != (vjY > testy))
                    && (testx < (vjX - viX) * (testy - viY) / (vjY - viY) + viX)) {
                inside = !inside;
            }
        }

        return inside;
    }

    /**
     * Calculates the planar area of a polygon using the shoelace formula, used to order the smallest
     * {@code AreaFeature}.
     */
    public static double calculatePlanarArea(List<Coordinate> vertices) {
        if (vertices == null || vertices.size() < 3) {
            return 0.0;
        }

        double area = 0.0;
        int n = vertices.size();
        double[] longitudes = unwrapLongitudes(vertices);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double nextLongitude = j == 0
                    ? longitudes[i] + normalizeLongitudeDelta(longitudes[0] - longitudes[i])
                    : longitudes[j];
            area += (longitudes[i] * vertices.get(j).latitude()) - (nextLongitude * vertices.get(i).latitude());
        }

        return Math.abs(area / 2.0);
    }

    /** Normalizes a longitude difference to the (-180, 180] range. */
    public static double normalizeLongitudeDelta(double delta) {
        return ((delta % 360.0) + 540.0) % 360.0 - 180.0;
    }

    private static double[] unwrapLongitudes(List<Coordinate> vertices) {
        double[] result = new double[vertices.size()];
        result[0] = vertices.get(0).longitude();
        for (int i = 1; i < vertices.size(); i++) {
            result[i] = result[i - 1] + normalizeLongitudeDelta(vertices.get(i).longitude() - result[i - 1]);
        }
        return result;
    }
}
