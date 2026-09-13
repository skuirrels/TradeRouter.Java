package io.github.skuirrels.traderouter.graph;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * High-performance bidirectional Dijkstra shortest-path solver. Uses node-indexed scratch arrays and
 * generation stamps instead of per-query maps.
 */
public final class BidirectionalDijkstra {
    private BidirectionalDijkstra() {
    }

    /**
     * Computes the shortest path and distance in kilometres between source and target nodes, avoiding any
     * passages present in the restrictions set.
     */
    public static PathResult findPath(MaritimeGraph graph, int source, int target, Set<String> restrictions) {
        if (source == target) {
            List<Coordinate> single = new ArrayList<>(1);
            single.add(graph.getCoordinate(source));
            return new PathResult(0.0, single);
        }

        boolean[] restricted = graph.restrictedPassages(restrictions);
        int[] offsets = graph.edgeOffsets();
        int[] targets = graph.edgeTargets();
        double[] weights = graph.edgeWeights();
        int[] passages = graph.edgePassageIds();
        int[] inOffsets = graph.incomingEdgeOffsets();
        int[] inTargets = graph.incomingEdgeTargets();
        double[] inWeights = graph.incomingEdgeWeights();
        int[] inPassages = graph.incomingEdgePassageIds();

        SearchBuffers buffers = SearchBuffers.acquire(graph.nodeCount());
        int gen = buffers.generation();
        double[] distF = buffers.distF;
        double[] distB = buffers.distB;
        int[] parentF = buffers.parentF;
        int[] parentB = buffers.parentB;
        int[] stampF = buffers.stampF;
        int[] stampB = buffers.stampB;
        IntDoubleHeap queueF = buffers.queueF;
        IntDoubleHeap queueB = buffers.queueB;

        distF[source] = 0.0;
        stampF[source] = gen;
        queueF.enqueue(source, 0.0);

        distB[target] = 0.0;
        stampB[target] = gen;
        queueB.enqueue(target, 0.0);

        double bestDistance = Double.POSITIVE_INFINITY;
        int meetNode = -1;

        while (!queueF.isEmpty() && !queueB.isEmpty()) {
            double minF = queueF.peekPriority();
            double minB = queueB.peekPriority();

            if (minF + minB >= bestDistance) {
                break;
            }

            if (minF <= minB) {
                // Expand forward side
                double prioF = minF;
                int u = queueF.dequeue();
                if (prioF > distF[u]) {
                    continue;
                }

                double dU = distF[u];

                for (int e = offsets[u], end = offsets[u + 1]; e < end; e++) {
                    int passage = passages[e];
                    if (passage != MaritimeGraph.NO_PASSAGE && restricted != null && restricted[passage]) {
                        continue; // Restricted passage skipped
                    }

                    int v = targets[e];
                    double cost = dU + weights[e];

                    if (stampF[v] != gen || cost < distF[v]) {
                        distF[v] = cost;
                        parentF[v] = u;
                        stampF[v] = gen;
                        queueF.enqueue(v, cost);

                        if (stampB[v] == gen) {
                            double total = cost + distB[v];
                            if (total < bestDistance) {
                                bestDistance = total;
                                meetNode = v;
                            }
                        }
                    }
                }
            } else {
                // Expand backward side
                double prioB = minB;
                int u = queueB.dequeue();
                if (prioB > distB[u]) {
                    continue;
                }

                double dU = distB[u];

                for (int e = inOffsets[u], end = inOffsets[u + 1]; e < end; e++) {
                    int passage = inPassages[e];
                    if (passage != MaritimeGraph.NO_PASSAGE && restricted != null && restricted[passage]) {
                        continue; // Restricted passage skipped
                    }

                    int v = inTargets[e];
                    double cost = dU + inWeights[e];

                    if (stampB[v] != gen || cost < distB[v]) {
                        distB[v] = cost;
                        parentB[v] = u;
                        stampB[v] = gen;
                        queueB.enqueue(v, cost);

                        if (stampF[v] == gen) {
                            double total = distF[v] + cost;
                            if (total < bestDistance) {
                                bestDistance = total;
                                meetNode = v;
                            }
                        }
                    }
                }
            }
        }

        if (meetNode == -1 || Double.isInfinite(bestDistance)) {
            return new PathResult(Double.POSITIVE_INFINITY, new ArrayList<>());
        }

        // Reconstruct path: source -> meetNode -> target
        int forwardLength = 1;
        for (int curr = meetNode; curr != source; curr = parentF[curr]) {
            forwardLength++;
        }

        int backwardLength = 0;
        for (int curr = meetNode; curr != target; curr = parentB[curr]) {
            backwardLength++;
        }

        Coordinate[] coordinates = new Coordinate[forwardLength + backwardLength];

        int index = forwardLength - 1;
        for (int curr = meetNode; ; curr = parentF[curr]) {
            coordinates[index--] = graph.coordinateUnchecked(curr);
            if (curr == source) {
                break;
            }
        }

        index = forwardLength;
        for (int curr = meetNode; curr != target; ) {
            curr = parentB[curr];
            coordinates[index++] = graph.coordinateUnchecked(curr);
        }

        List<Coordinate> path = new ArrayList<>(coordinates.length + 2);
        for (Coordinate coordinate : coordinates) {
            path.add(coordinate);
        }
        return new PathResult(bestDistance, path);
    }
}
