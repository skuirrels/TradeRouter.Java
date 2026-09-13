package io.github.skuirrels.traderouter.spatial;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;

/**
 * A node in a three-dimensional unit-sphere KD-tree.
 *
 * @param <T> payload type associated with the node
 */
public final class KdNode<T> {
    private final Coordinate point;
    private final T value;
    final double x;
    final double y;
    final double z;
    private KdNode<T> left;
    private KdNode<T> right;

    /** Creates a node at the given point with the given payload. */
    public KdNode(Coordinate point, T value) {
        point.validate();
        this.point = point;
        this.value = value;
        Haversine.UnitVector vector = Haversine.toUnitVector(point);
        this.x = vector.x();
        this.y = vector.y();
        this.z = vector.z();
    }

    /** Geographical position of the node. */
    public Coordinate point() {
        return point;
    }

    /** Payload value. */
    public T value() {
        return value;
    }

    /** Left child subtree, or null. */
    public KdNode<T> left() {
        return left;
    }

    /** Right child subtree, or null. */
    public KdNode<T> right() {
        return right;
    }

    void setLeft(KdNode<T> left) {
        this.left = left;
    }

    void setRight(KdNode<T> right) {
        this.right = right;
    }

    double axis(int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            default -> z;
        };
    }
}
