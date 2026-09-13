package io.github.skuirrels.traderouter.graph;

import java.util.Arrays;

/**
 * Per-thread reusable scratch state for graph searches. Node-indexed arrays replace per-query maps, and a
 * generation stamp marks which entries belong to the current query so no per-query clearing is required.
 */
final class SearchBuffers {
    private static final ThreadLocal<SearchBuffers> INSTANCE = ThreadLocal.withInitial(SearchBuffers::new);

    private int generation;

    /** Forward (or single-direction) tentative distances, valid when {@link #stampF} equals the generation. */
    double[] distF = new double[0];

    /** Backward tentative distances, valid when {@link #stampB} equals the generation. */
    double[] distB = new double[0];

    /** Forward parent pointers. */
    int[] parentF = new int[0];

    /** Backward parent pointers. */
    int[] parentB = new int[0];

    /** Generation stamp per node for the forward arrays. */
    int[] stampF = new int[0];

    /** Generation stamp per node for the backward arrays. */
    int[] stampB = new int[0];

    /** Generation stamp per node marking settled (closed) nodes. */
    int[] closed = new int[0];

    /** Forward priority queue, cleared between queries but retaining capacity. */
    final IntDoubleHeap queueF = new IntDoubleHeap(256);

    /** Backward priority queue, cleared between queries but retaining capacity. */
    final IntDoubleHeap queueB = new IntDoubleHeap(256);

    private SearchBuffers() {
    }

    /** The stamp value identifying entries written by the current query. */
    int generation() {
        return generation;
    }

    /** Returns the calling thread's buffers, sized for the node count and advanced to a fresh generation. */
    static SearchBuffers acquire(int nodeCount) {
        SearchBuffers buffers = INSTANCE.get();
        buffers.prepare(nodeCount);
        return buffers;
    }

    private void prepare(int nodeCount) {
        if (distF.length < nodeCount) {
            distF = new double[nodeCount];
            distB = new double[nodeCount];
            parentF = new int[nodeCount];
            parentB = new int[nodeCount];
            stampF = new int[nodeCount];
            stampB = new int[nodeCount];
            closed = new int[nodeCount];
            generation = 0;
        }

        if (generation == Integer.MAX_VALUE) {
            Arrays.fill(stampF, 0);
            Arrays.fill(stampB, 0);
            Arrays.fill(closed, 0);
            generation = 0;
        }

        generation++;
        queueF.clear();
        queueB.clear();
    }
}
