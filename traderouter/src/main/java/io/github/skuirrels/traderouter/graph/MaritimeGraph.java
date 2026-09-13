package io.github.skuirrels.traderouter.graph;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.spatial.KdItem;
import io.github.skuirrels.traderouter.spatial.KdNode;
import io.github.skuirrels.traderouter.spatial.KdTree;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory graph representing the global maritime shipping network (Marnet). Backed by forward and reverse
 * compressed sparse row layouts and a spherical KD-tree. Once {@link #buildIndex()} has been called the graph
 * is immutable and safe for concurrent route queries.
 */
public final class MaritimeGraph {
    /** Passage id stored on edges that carry no passage tag. */
    static final int NO_PASSAGE = -1;

    /** Edges shorter than this, one millimetre, are treated as zero-length when deriving the A* heuristic scale. */
    private static final double ZERO_LENGTH_KM = 1e-6;

    private final List<Coordinate> nodeCoordinates = new ArrayList<>();
    private final Map<Coordinate, Integer> coordToId = new HashMap<>();
    private final List<int[]> adjacencyTargets = new ArrayList<>();
    private final List<double[]> adjacencyWeights = new ArrayList<>();
    private final List<int[]> adjacencyPassages = new ArrayList<>();
    private final List<Integer> adjacencyCounts = new ArrayList<>();
    private final Map<Long, Integer> edgePassages = new HashMap<>();
    private final Map<String, Integer> passageIdsByLowerName = new HashMap<>();
    private final List<String> passageNames = new ArrayList<>();

    // Flattened adjacency built by buildIndex(): edges for node i live in [edgeOffsets[i], edgeOffsets[i + 1]).
    private int[] edgeOffsets;
    private int[] edgeTargets;
    private double[] edgeWeights;
    private int[] edgePassageIds;
    private int[] incomingEdgeOffsets;
    private int[] incomingEdgeTargets;
    private double[] incomingEdgeWeights;
    private int[] incomingEdgePassageIds;
    private KdTree<Integer> kdTree;
    private boolean readOnly;
    private double haversineHeuristicScale;
    private int mutableEdgeCount;

    /** Whether the graph has been indexed and frozen for concurrent route queries. */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** Scale applied to the geodesic A* heuristic so it remains admissible for custom edge weights. */
    public double haversineHeuristicScale() {
        return haversineHeuristicScale;
    }

    /** Number of nodes in the graph. */
    public int nodeCount() {
        return nodeCoordinates.size();
    }

    /** Number of directed edges in the graph. */
    public int edgeCount() {
        return edgeTargets != null ? edgeTargets.length : mutableEdgeCount;
    }

    /** Gets the coordinate of a node by its index. */
    public Coordinate getCoordinate(int nodeId) {
        ensureNodeId(nodeId, "nodeId");
        return nodeCoordinates.get(nodeId);
    }

    /** Gets the outgoing edges of a node. */
    public List<GraphEdge> getEdges(int nodeId) {
        ensureNodeId(nodeId, "nodeId");
        if (edgeTargets != null) {
            return new EdgeView(edgeTargets, edgeWeights, edgePassageIds, edgeOffsets[nodeId], edgeOffsets[nodeId + 1]);
        }
        return new EdgeView(adjacencyTargets.get(nodeId), adjacencyWeights.get(nodeId), adjacencyPassages.get(nodeId),
                0, adjacencyCounts.get(nodeId));
    }

    /** Gets the incoming edges of a node. Each returned target is the predecessor node. */
    public List<GraphEdge> getIncomingEdges(int nodeId) {
        ensureNodeId(nodeId, "nodeId");
        if (incomingEdgeTargets == null) {
            throw new IllegalStateException("Graph index has not been built. Call buildIndex() first.");
        }
        return new EdgeView(incomingEdgeTargets, incomingEdgeWeights, incomingEdgePassageIds,
                incomingEdgeOffsets[nodeId], incomingEdgeOffsets[nodeId + 1]);
    }

    /** Gets the passage associated with an edge, or null. */
    public String getPassage(int u, int v) {
        Integer passageId = edgePassages.get(edgeKey(u, v));
        return passageId == null || passageId == NO_PASSAGE ? null : passageNames.get(passageId);
    }

    /** Gets the passage associated with the edge between two coordinates, or null. */
    public String getPassage(Coordinate u, Coordinate v) {
        Integer uId = coordToId.get(u);
        Integer vId = coordToId.get(v);
        if (uId != null && vId != null) {
            return getPassage(uId, vId);
        }
        return null;
    }

    /**
     * Adds a node with its coordinate, returning its assigned integer node id. Nodes can only be added before
     * {@link #buildIndex()} freezes the graph.
     */
    public int addNode(Coordinate coord) {
        ensureMutable();
        coord.validate();
        if (coordToId.containsKey(coord)) {
            throw new IllegalArgumentException("A graph node already exists at " + coord + ".");
        }
        int id = nodeCoordinates.size();
        nodeCoordinates.add(coord);
        coordToId.put(coord, id);
        adjacencyTargets.add(new int[2]);
        adjacencyWeights.add(new double[2]);
        adjacencyPassages.add(new int[2]);
        adjacencyCounts.add(0);
        return id;
    }

    /** Adds a directed edge without a passage tag. */
    public void addDirectedEdge(int uId, int vId, double weight) {
        addDirectedEdge(uId, vId, weight, null);
    }

    /**
     * Adds a directed edge from node uId to node vId with the specified weight and optional passage. Edges can
     * only be added before {@link #buildIndex()} freezes the graph.
     */
    public void addDirectedEdge(int uId, int vId, double weight, String passage) {
        ensureMutable();
        ensureNodeId(uId, "uId");
        ensureNodeId(vId, "vId");
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException("Edge weight must be finite and non-negative. Received: " + weight);
        }
        int passageId = passageId(passage);
        int count = adjacencyCounts.get(uId);
        int[] targets = adjacencyTargets.get(uId);
        if (count == targets.length) {
            int newLength = targets.length * 2;
            targets = java.util.Arrays.copyOf(targets, newLength);
            adjacencyTargets.set(uId, targets);
            adjacencyWeights.set(uId, java.util.Arrays.copyOf(adjacencyWeights.get(uId), newLength));
            adjacencyPassages.set(uId, java.util.Arrays.copyOf(adjacencyPassages.get(uId), newLength));
        }
        targets[count] = vId;
        adjacencyWeights.get(uId)[count] = weight;
        adjacencyPassages.get(uId)[count] = passageId;
        adjacencyCounts.set(uId, count + 1);
        mutableEdgeCount++;
        edgePassages.put(edgeKey(uId, vId), passageId);
    }

    /** Adds a node if it does not already exist, returning its integer node id. */
    public int getOrAddNode(Coordinate coord) {
        ensureMutable();
        coord.validate();
        Integer id = coordToId.get(coord);
        if (id != null) {
            return id;
        }
        return addNode(coord);
    }

    /** Adds an undirected edge between coordinates u and v weighted by their great-circle distance. */
    public void addEdge(Coordinate u, Coordinate v) {
        addEdge(u, v, null, null);
    }

    /**
     * Adds an undirected edge between coordinates u and v. When the weight is null the great-circle distance in
     * kilometres, rounded to one decimal, is used. Edges can only be added before {@link #buildIndex()}.
     */
    public void addEdge(Coordinate u, Coordinate v, Double weight, String passage) {
        ensureMutable();
        int uId = getOrAddNode(u);
        int vId = getOrAddNode(v);

        double w = weight != null ? weight : Math.rint(Haversine.distance(u, v, DistanceUnit.KM) * 10.0) / 10.0;

        addDirectedEdge(uId, vId, w, passage);
        addDirectedEdge(vId, uId, w, passage);
    }

    /**
     * Finalizes graph construction: flattens the adjacency lists into a compressed sparse row layout and builds
     * the KD-tree index. Must be called after all nodes and edges have been added.
     */
    public void buildIndex() {
        if (readOnly) {
            return;
        }

        int nodeCount = nodeCoordinates.size();
        int[] offsets = new int[nodeCount + 1];
        for (int i = 0; i < nodeCount; i++) {
            offsets[i + 1] = offsets[i] + adjacencyCounts.get(i);
        }

        int totalEdges = offsets[nodeCount];
        int[] targets = new int[totalEdges];
        double[] weights = new double[totalEdges];
        int[] passages = new int[totalEdges];
        int[] incomingCounts = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            int count = adjacencyCounts.get(i);
            System.arraycopy(adjacencyTargets.get(i), 0, targets, offsets[i], count);
            System.arraycopy(adjacencyWeights.get(i), 0, weights, offsets[i], count);
            System.arraycopy(adjacencyPassages.get(i), 0, passages, offsets[i], count);
            int[] nodeTargets = adjacencyTargets.get(i);
            for (int e = 0; e < count; e++) {
                incomingCounts[nodeTargets[e]]++;
            }
        }

        int[] incomingOffsets = new int[nodeCount + 1];
        for (int i = 0; i < nodeCount; i++) {
            incomingOffsets[i + 1] = incomingOffsets[i] + incomingCounts[i];
        }

        int[] incomingTargets = new int[totalEdges];
        double[] incomingWeights = new double[totalEdges];
        int[] incomingPassages = new int[totalEdges];
        int[] incomingPositions = incomingOffsets.clone();
        double heuristicScale = 1.0;
        for (int source = 0; source < nodeCount; source++) {
            for (int e = offsets[source]; e < offsets[source + 1]; e++) {
                int target = targets[e];
                int position = incomingPositions[target]++;
                incomingTargets[position] = source;
                incomingWeights[position] = weights[e];
                incomingPassages[position] = passages[e];
                // Zero-length seam edges joining the -180 and +180 copies of a node measure a few picometres
                // rather than exactly zero because sin(pi) is not exactly zero in floating point. Ignoring
                // anything under a millimetre keeps them from collapsing the scale to zero, which would turn
                // A* into plain Dijkstra.
                double directDistance = Haversine.distanceKmUnchecked(nodeCoordinates.get(source), nodeCoordinates.get(target));
                if (directDistance > ZERO_LENGTH_KM) {
                    heuristicScale = Math.min(heuristicScale, weights[e] / directDistance);
                }
            }
        }

        List<KdItem<Integer>> items = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            items.add(new KdItem<>(nodeCoordinates.get(i), i));
        }

        edgeOffsets = offsets;
        edgeTargets = targets;
        edgeWeights = weights;
        edgePassageIds = passages;
        incomingEdgeOffsets = incomingOffsets;
        incomingEdgeTargets = incomingTargets;
        incomingEdgeWeights = incomingWeights;
        incomingEdgePassageIds = incomingPassages;
        kdTree = new KdTree<>(items);
        haversineHeuristicScale = Math.clamp(heuristicScale, 0.0, 1.0);
        readOnly = true;

        adjacencyTargets.clear();
        adjacencyWeights.clear();
        adjacencyPassages.clear();
        adjacencyCounts.clear();
    }

    /** Finds the nearest graph node index to the given geographic coordinate. */
    public int findNearestNode(Coordinate coordinate) {
        if (kdTree == null) {
            throw new IllegalStateException("Graph index has not been built. Call buildIndex() first.");
        }

        KdNode<Integer> nearest = kdTree.nearest(coordinate);
        if (nearest == null) {
            throw new IllegalStateException("No nodes in maritime graph.");
        }

        return nearest.value();
    }

    /** Computes the shortest path between two coordinates with bidirectional Dijkstra and no restrictions. */
    public PathResult shortestPath(Coordinate origin, Coordinate destination) {
        return shortestPath(origin, destination, null, "dijkstra");
    }

    /**
     * Computes the shortest path on the maritime graph between two coordinates, applying passage restrictions.
     *
     * @param origin       start coordinate, snapped to the nearest node
     * @param destination  end coordinate, snapped to the nearest node
     * @param restrictions passage tags whose edges are skipped, matched case-insensitively; may be null
     * @param algorithm    "dijkstra" (default when null) or "astar"
     */
    public PathResult shortestPath(Coordinate origin, Coordinate destination, Set<String> restrictions, String algorithm) {
        if (!readOnly) {
            throw new IllegalStateException("Graph index has not been built. Call buildIndex() first.");
        }
        int originNodeId = findNearestNode(origin);
        int destNodeId = findNearestNode(destination);

        if (originNodeId == destNodeId) {
            List<Coordinate> single = new ArrayList<>(1);
            single.add(nodeCoordinates.get(originNodeId));
            return new PathResult(0.0, single);
        }

        String chosen = algorithm == null ? "dijkstra" : algorithm;
        if (chosen.equalsIgnoreCase("astar")) {
            return AStar.findPath(this, originNodeId, destNodeId, restrictions);
        }

        if (!chosen.equalsIgnoreCase("dijkstra")) {
            throw new IllegalArgumentException(
                    "Unknown pathfinding algorithm '" + algorithm + "'. Expected 'dijkstra' or 'astar'.");
        }

        return BidirectionalDijkstra.findPath(this, originNodeId, destNodeId, restrictions);
    }

    // Package-private compressed sparse row accessors for the search algorithms.

    int[] edgeOffsets() {
        return edgeOffsets;
    }

    int[] edgeTargets() {
        return edgeTargets;
    }

    double[] edgeWeights() {
        return edgeWeights;
    }

    int[] edgePassageIds() {
        return edgePassageIds;
    }

    int[] incomingEdgeOffsets() {
        return incomingEdgeOffsets;
    }

    int[] incomingEdgeTargets() {
        return incomingEdgeTargets;
    }

    double[] incomingEdgeWeights() {
        return incomingEdgeWeights;
    }

    int[] incomingEdgePassageIds() {
        return incomingEdgePassageIds;
    }

    Coordinate coordinateUnchecked(int nodeId) {
        return nodeCoordinates.get(nodeId);
    }

    /**
     * Converts a set of restricted passage names into a lookup indexed by passage id, or null when nothing is
     * restricted.
     */
    boolean[] restrictedPassages(Set<String> restrictions) {
        if (restrictions == null || restrictions.isEmpty() || passageNames.isEmpty()) {
            return null;
        }
        boolean[] restricted = null;
        for (String restriction : restrictions) {
            if (restriction == null) {
                continue;
            }
            Integer id = passageIdsByLowerName.get(restriction.toLowerCase(Locale.ROOT));
            if (id != null) {
                if (restricted == null) {
                    restricted = new boolean[passageNames.size()];
                }
                restricted[id] = true;
            }
        }
        return restricted;
    }

    private int passageId(String passage) {
        if (passage == null) {
            return NO_PASSAGE;
        }
        String key = passage.toLowerCase(Locale.ROOT);
        Integer id = passageIdsByLowerName.get(key);
        if (id == null) {
            id = passageNames.size();
            passageNames.add(passage);
            passageIdsByLowerName.put(key, id);
        }
        return id;
    }

    private static long edgeKey(int u, int v) {
        return (((long) u) << 32) | (v & 0xFFFFFFFFL);
    }

    private void ensureMutable() {
        if (readOnly) {
            throw new IllegalStateException(
                    "The graph is read-only after buildIndex(). Build a new graph to change its nodes or edges.");
        }
    }

    private void ensureNodeId(int nodeId, String parameterName) {
        if (nodeId < 0 || nodeId >= nodeCoordinates.size()) {
            throw new IllegalArgumentException(
                    parameterName + " must be between 0 and " + (nodeCoordinates.size() - 1) + ". Received: " + nodeId);
        }
    }

    private final class EdgeView extends AbstractList<GraphEdge> {
        private final int[] targets;
        private final double[] weights;
        private final int[] passages;
        private final int start;
        private final int end;

        EdgeView(int[] targets, double[] weights, int[] passages, int start, int end) {
            this.targets = targets;
            this.weights = weights;
            this.passages = passages;
            this.start = start;
            this.end = end;
        }

        @Override
        public GraphEdge get(int index) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException(index);
            }
            int e = start + index;
            int passageId = passages[e];
            return new GraphEdge(targets[e], weights[e], passageId == NO_PASSAGE ? null : passageNames.get(passageId));
        }

        @Override
        public int size() {
            return end - start;
        }
    }
}
