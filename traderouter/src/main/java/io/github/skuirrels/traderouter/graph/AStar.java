package io.github.skuirrels.traderouter.graph;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A* shortest-path solver using Haversine great-circle distance as heuristic. Uses node-indexed scratch
 * arrays and generation stamps instead of per-query maps.
 */
public final class AStar {
    private AStar() {
    }

    /** Computes the shortest path using A* search. */
    public static PathResult findPath(MaritimeGraph graph, int source, int target, Set<String> restrictions) {
        if (source == target) {
            List<Coordinate> single = new ArrayList<>(1);
            single.add(graph.getCoordinate(source));
            return new PathResult(0.0, single);
        }

        Coordinate targetCoord = graph.getCoordinate(target);
        boolean[] restricted = graph.restrictedPassages(restrictions);
        int[] offsets = graph.edgeOffsets();
        int[] targets = graph.edgeTargets();
        double[] weights = graph.edgeWeights();
        int[] passages = graph.edgePassageIds();
        double heuristicScale = graph.haversineHeuristicScale();
        // A scale of zero means no geodesic heuristic is admissible for this graph's weights, so the search is
        // plain Dijkstra; skip the Haversine work in that case.
        boolean useHeuristic = heuristicScale > 0.0;

        SearchBuffers buffers = SearchBuffers.acquire(graph.nodeCount());
        int gen = buffers.generation();
        double[] gScore = buffers.distF;
        int[] parent = buffers.parentF;
        int[] stamp = buffers.stampF;
        int[] closed = buffers.closed;
        IntDoubleHeap openSet = buffers.queueF;

        gScore[source] = 0.0;
        stamp[source] = gen;
        double h0 = useHeuristic ? heuristicScale * Haversine.distanceKmUnchecked(graph.coordinateUnchecked(source), targetCoord) : 0.0;
        openSet.enqueue(source, h0);

        while (!openSet.isEmpty()) {
            int current = openSet.dequeue();

            if (current == target) {
                // Reconstruct path
                int length = 1;
                for (int curr = target; curr != source; curr = parent[curr]) {
                    length++;
                }

                Coordinate[] path = new Coordinate[length];
                int index = length - 1;
                for (int curr = target; ; curr = parent[curr]) {
                    path[index--] = graph.coordinateUnchecked(curr);
                    if (curr == source) {
                        break;
                    }
                }

                List<Coordinate> result = new ArrayList<>(length + 2);
                for (Coordinate coordinate : path) {
                    result.add(coordinate);
                }
                return new PathResult(gScore[target], result);
            }

            if (closed[current] == gen) {
                continue;
            }
            closed[current] = gen;

            double currentG = gScore[current];

            for (int e = offsets[current], end = offsets[current + 1]; e < end; e++) {
                int passage = passages[e];
                if (passage != MaritimeGraph.NO_PASSAGE && restricted != null && restricted[passage]) {
                    continue;
                }

                int neighbor = targets[e];
                if (closed[neighbor] == gen) {
                    continue;
                }

                double tentativeG = currentG + weights[e];

                if (stamp[neighbor] != gen || tentativeG < gScore[neighbor]) {
                    gScore[neighbor] = tentativeG;
                    parent[neighbor] = current;
                    stamp[neighbor] = gen;
                    double h = useHeuristic
                            ? heuristicScale * Haversine.distanceKmUnchecked(graph.coordinateUnchecked(neighbor), targetCoord)
                            : 0.0;
                    openSet.enqueue(neighbor, tentativeG + h);
                }
            }
        }

        return new PathResult(Double.POSITIVE_INFINITY, new ArrayList<>());
    }
}
