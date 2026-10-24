package io.github.skuirrels.traderouter.seaareas;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Exercises the package-private index on small hand-drawn areas. */
class SeaAreaIndexTests {
    @Test
    void traverseMergesAReturnThroughAnUnlistedHole() {
        SeaAreaIndex index = new SeaAreaIndex(List.of(area("ocean", square(0, 0, 10, 10), square(4, 4, 6, 6))));

        List<SeaAreaTraversal> areas = index.traverse(List.of(new Coordinate(1, 5), new Coordinate(9, 5)));

        assertThat(areas).hasSize(1);
        assertThat(areas.get(0).enterKm()).isEqualTo(0.0);
        assertThat(areas.get(0).exitKm())
                .isCloseTo(Haversine.distanceKm(new Coordinate(1, 5), new Coordinate(9, 5)), within(1e-6));
    }

    @Test
    void traverseListsAReturnSeparatelyWhenAnotherListedAreaLiesBetween() {
        SeaAreaIndex index = new SeaAreaIndex(List.of(
                area("ocean", square(0, 0, 10, 10), square(4, 4, 6, 6)),
                area("sea", square(4, 4, 6, 6))));

        List<SeaAreaTraversal> areas = index.traverse(List.of(new Coordinate(1, 5), new Coordinate(9, 5)));

        assertThat(areas).extracting(SeaAreaTraversal::id).containsExactly("ocean", "sea", "ocean");
        assertThat(areas.get(0).exitKm()).isCloseTo(areas.get(1).enterKm(), within(1e-6));
        assertThat(areas.get(1).exitKm()).isCloseTo(areas.get(2).enterKm(), within(1e-6));
        assertThat(areas.get(1).exitKm())
                .isCloseTo(Haversine.distanceKm(new Coordinate(1, 5), new Coordinate(6, 5)), within(0.05));
    }

    @Test
    void traverseListsAReturnSeparatelyWhenAnOverlappingAreaSpansTheGap() {
        // The route is already inside "sea" when it leaves "ocean" for the hole, and still inside it on return.
        SeaAreaIndex index = new SeaAreaIndex(List.of(
                area("ocean", square(0, 0, 10, 10), square(4, 4, 6, 6)),
                area("sea", square(3, 4, 7, 6))));

        List<SeaAreaTraversal> areas = index.traverse(List.of(new Coordinate(1, 5), new Coordinate(9, 5)));

        assertThat(areas).extracting(SeaAreaTraversal::id).containsExactly("ocean", "sea", "ocean");
    }

    @Test
    void traverseListsEveryOverlappingAreaAndIgnoresUnlistedWater() {
        SeaAreaIndex index = new SeaAreaIndex(List.of(
                area("wide", square(0, 0, 10, 10)),
                area("narrow", square(3, -5, 7, 15))));

        List<SeaAreaTraversal> areas = index.traverse(List.of(new Coordinate(-5, 5), new Coordinate(15, 5)));

        assertThat(areas).extracting(SeaAreaTraversal::id).containsExactly("wide", "narrow");
        assertThat(areas.get(0).enterKm())
                .isCloseTo(Haversine.distanceKm(new Coordinate(-5, 5), new Coordinate(0, 5)), within(0.05));
        assertThat(areas.get(1).enterKm())
                .isCloseTo(Haversine.distanceKm(new Coordinate(-5, 5), new Coordinate(3, 5)), within(0.05));
        assertThat(areas.get(1).exitKm()).isLessThan(areas.get(0).exitKm());
        assertThat(index.traverse(List.of(new Coordinate(20, 20), new Coordinate(25, 25)))).isEmpty();
    }

    @Test
    void traverseFollowsAnAreaSplitAtTheAntimeridianAsOneEntry() {
        SeaAreaIndex index = new SeaAreaIndex(List.of(area("pacific", square(170, 0, 180, 10), square(-180, 0, -170, 10))));

        List<SeaAreaTraversal> continuous = index.traverse(List.of(new Coordinate(175, 5), new Coordinate(185, 5)));
        List<SeaAreaTraversal> wrapped = index.traverse(List.of(new Coordinate(175, 5), new Coordinate(-175, 5)));

        double lengthKm = Haversine.distanceKm(new Coordinate(175, 5), new Coordinate(-175, 5));
        assertThat(continuous).hasSize(1);
        assertThat(continuous.get(0).exitKm()).isCloseTo(lengthKm, within(1e-6));
        assertThat(wrapped).hasSize(1);
        assertThat(wrapped.get(0).exitKm()).isCloseTo(lengthKm, within(1e-6));
        assertThat(wrapped.get(0).enterKm()).isEqualTo(0.0);
    }

    private static SeaAreaIndex.Area area(String id, double[]... rings) {
        return new SeaAreaIndex.Area(new SeaAreaInfo(id, id, "Q1", id.hashCode()), Arrays.asList(rings));
    }

    private static double[] square(double minX, double minY, double maxX, double maxY) {
        return new double[] {minX, minY, maxX, minY, maxX, maxY, minX, maxY, minX, minY};
    }
}
