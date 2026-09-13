package io.github.skuirrels.traderouter.spatial;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A balanced three-dimensional KD-tree over unit-sphere coordinates. This preserves exact great-circle
 * nearest-neighbour ordering across the antimeridian and near the poles. Thread-safe for read queries once
 * constructed.
 *
 * @param <T> payload type associated with coordinates
 */
public final class KdTree<T> {
    private final KdNode<T> root;
    private final int count;

    /** Builds a balanced spherical KD-tree from point-value pairs. */
    public KdTree(Collection<KdItem<T>> items) {
        Objects.requireNonNull(items, "items");
        List<KdNode<T>> nodes = new ArrayList<>(items.size());
        for (KdItem<T> item : items) {
            nodes.add(new KdNode<>(item.point(), item.value()));
        }
        count = nodes.size();
        root = buildTree(nodes, 0, nodes.size(), 0);
    }

    /** Builds a tree whose payload is the coordinate itself. */
    public static KdTree<Coordinate> fromCoordinates(Collection<Coordinate> coordinates) {
        List<KdItem<Coordinate>> items = new ArrayList<>(coordinates.size());
        for (Coordinate coordinate : coordinates) {
            items.add(new KdItem<>(coordinate, coordinate));
        }
        return new KdTree<>(items);
    }

    /** Number of nodes in the tree. */
    public int count() {
        return count;
    }

    /** Finds the geographically nearest neighbour to the target, or empty when the tree is empty. */
    public Optional<KdItem<T>> query(Coordinate target) {
        KdNode<T> best = nearest(target);
        return best == null ? Optional.empty() : Optional.of(new KdItem<>(best.point(), best.value()));
    }

    /** Finds the geographically nearest node to the target, or null when the tree is empty. */
    public KdNode<T> nearest(Coordinate target) {
        if (root == null) {
            return null;
        }

        Haversine.UnitVector vector = Haversine.toUnitVector(target);
        Search<T> search = new Search<>();
        queryRecursive(root, vector.x(), vector.y(), vector.z(), 0, search);
        return search.best;
    }

    private static final class Search<T> {
        KdNode<T> best;
        double bestDistanceSquared = Double.POSITIVE_INFINITY;
    }

    private static <T> KdNode<T> buildTree(List<KdNode<T>> nodes, int start, int length, int depth) {
        if (length <= 0) {
            return null;
        }

        int axis = depth % 3;
        nodes.subList(start, start + length).sort(Comparator.comparingDouble(node -> node.axis(axis)));
        int medianOffset = length / 2;
        int medianIndex = start + medianOffset;
        KdNode<T> node = nodes.get(medianIndex);
        node.setLeft(buildTree(nodes, start, medianOffset, depth + 1));
        node.setRight(buildTree(nodes, medianIndex + 1, length - medianOffset - 1, depth + 1));
        return node;
    }

    private static <T> void queryRecursive(
            KdNode<T> current, double tx, double ty, double tz, int depth, Search<T> search) {
        double dx = tx - current.x;
        double dy = ty - current.y;
        double dz = tz - current.z;
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (distanceSquared < search.bestDistanceSquared) {
            search.bestDistanceSquared = distanceSquared;
            search.best = current;
        }

        int axis = depth % 3;
        double targetAxis = switch (axis) {
            case 0 -> tx;
            case 1 -> ty;
            default -> tz;
        };
        double delta = targetAxis - current.axis(axis);
        KdNode<T> near = delta < 0 ? current.left() : current.right();
        KdNode<T> far = delta < 0 ? current.right() : current.left();

        if (near != null) {
            queryRecursive(near, tx, ty, tz, depth + 1, search);
        }
        if (far != null && (delta * delta) < search.bestDistanceSquared) {
            queryRecursive(far, tx, ty, tz, depth + 1, search);
        }
    }
}
