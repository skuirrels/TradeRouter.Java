package io.github.skuirrels.traderouter.graph;

import java.util.Arrays;

/**
 * Minimum priority queue of int elements keyed by double priorities, backed by a quaternary (4-ary) heap in
 * primitive arrays. The sift operations match the .NET {@code PriorityQueue<int, double>} implementation so
 * that equal-cost ties break the same way as in TradeRouter.Net and the two libraries emit identical
 * routes.
 */
final class IntDoubleHeap {
    private static final int ARITY = 4;
    private static final int LOG2_ARITY = 2;

    private int[] elements;
    private double[] priorities;
    private int size;

    IntDoubleHeap(int initialCapacity) {
        elements = new int[initialCapacity];
        priorities = new double[initialCapacity];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }

    /** The priority of the minimum element. The heap must not be empty. */
    double peekPriority() {
        return priorities[0];
    }

    /** The minimum element. The heap must not be empty. */
    int peekElement() {
        return elements[0];
    }

    void enqueue(int element, double priority) {
        int currentSize = size;
        if (elements.length == currentSize) {
            grow();
        }
        size = currentSize + 1;
        moveUp(element, priority, currentSize);
    }

    /** Removes and returns the minimum element. The heap must not be empty. */
    int dequeue() {
        int element = elements[0];
        removeRootNode();
        return element;
    }

    private void removeRootNode() {
        int lastNodeIndex = --size;
        if (lastNodeIndex > 0) {
            int lastElement = elements[lastNodeIndex];
            double lastPriority = priorities[lastNodeIndex];
            moveDown(lastElement, lastPriority, 0);
        }
    }

    private void moveUp(int element, double priority, int nodeIndex) {
        int[] nodes = elements;
        double[] prios = priorities;

        while (nodeIndex > 0) {
            int parentIndex = (nodeIndex - 1) >> LOG2_ARITY;
            double parentPriority = prios[parentIndex];
            if (Double.compare(priority, parentPriority) < 0) {
                nodes[nodeIndex] = nodes[parentIndex];
                prios[nodeIndex] = parentPriority;
                nodeIndex = parentIndex;
            } else {
                break;
            }
        }

        nodes[nodeIndex] = element;
        prios[nodeIndex] = priority;
    }

    private void moveDown(int element, double priority, int nodeIndex) {
        int[] nodes = elements;
        double[] prios = priorities;
        int currentSize = size;

        int i;
        while ((i = (nodeIndex << LOG2_ARITY) + 1) < currentSize) {
            int minChildIndex = i;
            double minChildPriority = prios[i];
            int childIndexUpperBound = Math.min(i + ARITY, currentSize);
            while (++i < childIndexUpperBound) {
                double nextChildPriority = prios[i];
                if (Double.compare(nextChildPriority, minChildPriority) < 0) {
                    minChildPriority = nextChildPriority;
                    minChildIndex = i;
                }
            }

            if (Double.compare(priority, minChildPriority) <= 0) {
                break;
            }

            nodes[nodeIndex] = nodes[minChildIndex];
            prios[nodeIndex] = minChildPriority;
            nodeIndex = minChildIndex;
        }

        nodes[nodeIndex] = element;
        prios[nodeIndex] = priority;
    }

    private void grow() {
        int newCapacity = Math.max(elements.length * 2, 4);
        elements = Arrays.copyOf(elements, newCapacity);
        priorities = Arrays.copyOf(priorities, newCapacity);
    }
}
