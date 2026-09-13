package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.spatial.KdItem;
import io.github.skuirrels.traderouter.spatial.KdTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class KdTreeTests {

    @Test
    void emptyTreeReturnsEmpty() {
        KdTree<String> tree = new KdTree<>(List.of());
        assertThat(tree.count()).isZero();
        assertThat(tree.query(new Coordinate(0, 0))).isEmpty();
    }

    @Test
    void singleElementReturnsThatElement() {
        Coordinate pt = new Coordinate(10.0, 20.0);
        KdTree<String> tree = new KdTree<>(List.of(new KdItem<>(pt, "Item1")));
        assertThat(tree.count()).isEqualTo(1);

        Optional<KdItem<String>> query = tree.query(new Coordinate(100.0, 20.0));
        assertThat(query).isPresent();
        assertThat(query.get().value()).isEqualTo("Item1");
        assertThat(query.get().point()).isEqualTo(pt);
    }

    @Test
    void multiplePointsFindsExactNearestNeighbor() {
        List<KdItem<Integer>> points = List.of(
                new KdItem<>(new Coordinate(0, 0), 0),
                new KdItem<>(new Coordinate(10, 10), 1),
                new KdItem<>(new Coordinate(-10, -10), 2),
                new KdItem<>(new Coordinate(5, 5), 3),
                new KdItem<>(new Coordinate(-5, 5), 4),
                new KdItem<>(new Coordinate(20, -20), 5));

        KdTree<Integer> tree = new KdTree<>(points);
        assertThat(tree.count()).isEqualTo(points.size());

        // Closest to (4.9, 5.1) is (5, 5) => id 3
        assertThat(tree.query(new Coordinate(4.9, 5.1))).get().extracting(KdItem::value).isEqualTo(3);

        // Closest to (-9.0, -9.5) is (-10, -10) => id 2
        assertThat(tree.query(new Coordinate(-9.0, -9.5))).get().extracting(KdItem::value).isEqualTo(2);
    }

    @Test
    void randomCoordinatesMatchLinearBruteForceSearch() {
        Random rand = new Random(42);
        List<KdItem<Integer>> items = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            double lon = (rand.nextDouble() * 360.0) - 180.0;
            double lat = (rand.nextDouble() * 180.0) - 90.0;
            items.add(new KdItem<>(new Coordinate(lon, lat), i));
        }

        KdTree<Integer> tree = new KdTree<>(items);

        for (int q = 0; q < 50; q++) {
            double qLon = (rand.nextDouble() * 360.0) - 180.0;
            double qLat = (rand.nextDouble() * 180.0) - 90.0;
            Coordinate qCoord = new Coordinate(qLon, qLat);

            // Brute force
            double bestDistSq = Double.POSITIVE_INFINITY;
            for (KdItem<Integer> item : items) {
                double dSq = Haversine.unitSphereDistanceSquared(qCoord, item.point());
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                }
            }

            Optional<KdItem<Integer>> treeResult = tree.query(qCoord);
            assertThat(treeResult).isPresent();

            // The distance to tree result point should be identical to best distance
            double treeDistSq = Haversine.unitSphereDistanceSquared(qCoord, treeResult.get().point());
            assertThat(treeDistSq).isCloseTo(bestDistSq, within(1e-9));
        }
    }

    @Test
    void antimeridianSelectsAcrossDateLineNeighbor() {
        Coordinate acrossDateLine = new Coordinate(179.8, 0);
        Coordinate geographicallyFarther = new Coordinate(-160, 0);
        KdTree<String> tree = new KdTree<>(List.of(
                new KdItem<>(acrossDateLine, "across"),
                new KdItem<>(geographicallyFarther, "farther")));

        assertThat(tree.query(new Coordinate(-179.9, 0))).get().extracting(KdItem::value).isEqualTo("across");
    }

    @Test
    void nearPoleMatchesSphericalNearestNeighbor() {
        Coordinate closeAcrossLongitude = new Coordinate(90, 86);
        Coordinate fartherDownMeridian = new Coordinate(0, 70);
        KdTree<String> tree = new KdTree<>(List.of(
                new KdItem<>(closeAcrossLongitude, "polar"),
                new KdItem<>(fartherDownMeridian, "southern")));

        assertThat(tree.query(new Coordinate(0, 85))).get().extracting(KdItem::value).isEqualTo("polar");
    }
}
