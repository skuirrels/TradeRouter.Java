package io.github.skuirrels.traderouter.common;

import io.github.skuirrels.traderouter.graph.MaritimeGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles coordinate normalization across the antimeridian (±180° longitude) to avoid wrapping artefacts
 * across world maps.
 */
public final class RouteNormalizer {
    private RouteNormalizer() {
    }

    /**
     * A normalized route and the passages it traverses.
     *
     * @param lineString         normalized coordinates
     * @param traversedPassages  passage tags of the edges used, in traversal order, possibly with repeats
     */
    public record ProcessedRoute(List<Coordinate> lineString, List<String> traversedPassages) {
    }

    /**
     * Normalizes the current coordinate relative to the previous coordinate in a line string.
     *
     * @param prev the previous coordinate, or null for the first coordinate
     * @param now  the coordinate to normalize
     */
    public static Coordinate normalizeLineString(Coordinate prev, Coordinate now) {
        if (prev == null) {
            return now;
        }

        double nowX = now.longitude();
        double nowY = now.latitude();
        double prevX = prev.longitude();

        double px = prevX - nowX;
        if (px < -180.0) {
            nowX -= 360.0;
        } else if (px > 180.0) {
            nowX += 360.0;
        }

        return new Coordinate(nowX, nowY);
    }

    /** Normalizes a sequence of coordinates along a path. */
    public static List<Coordinate> normalizeRoute(List<Coordinate> route) {
        if (route == null || route.isEmpty()) {
            return new ArrayList<>();
        }

        List<Coordinate> result = new ArrayList<>(route.size());
        Coordinate previous = null;

        for (Coordinate coord : route) {
            Coordinate normalized = normalizeLineString(previous, coord);
            result.add(normalized);
            previous = normalized;
        }

        return result;
    }

    /** Normalizes coordinates and extracts traversed passages from the maritime graph along the route. */
    public static ProcessedRoute processRoute(List<Coordinate> route, MaritimeGraph graph, boolean returnPassages) {
        if (route == null || route.isEmpty()) {
            return new ProcessedRoute(new ArrayList<>(), new ArrayList<>());
        }

        List<Coordinate> ls = new ArrayList<>(route.size());
        Coordinate previous = null;
        Coordinate previousTraversed = null;
        List<String> traversedPassages = new ArrayList<>();

        for (Coordinate now : route) {
            if (returnPassages && previousTraversed != null) {
                String passage = graph.getPassage(previousTraversed, now);
                if (passage != null && !passage.isEmpty()) {
                    traversedPassages.add(passage);
                }
            }

            Coordinate fixedCoords = normalizeLineString(previous, now);
            ls.add(fixedCoords);
            previous = fixedCoords;
            previousTraversed = now;
        }

        return new ProcessedRoute(ls, traversedPassages);
    }
}
