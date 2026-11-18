package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.locations.UnLocodeDatabase;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codes added add-only from the UNECE 2025-1 release by tools/merge-unlocode-release.py.
 */
class UnLocodeReleaseMergeTests {
    private static UnLocodeDatabase unLocodes() {
        return DefaultTradeRouterEngine.getDefault().unLocodes();
    }

    @Test
    void databaseHoldsTheOriginalImportPlusTheAddedCodes() {
        assertThat(unLocodes().count()).isEqualTo(116_022);
        assertThat(unLocodes().countWithCoordinates()).isEqualTo(113_541);
    }

    @ParameterizedTest
    @CsvSource({
            "ZAZCO, Malmesbury, 18.73333, -33.45",
            "DEAL9, Allach, 11.45, 48.2",
            "THCHO, Chonburi, 100.98333, 13.36667",
            "VNTNT, Tay Ninh, 106.1, 11.3"
    })
    void locateResolvesAddedCodesAtTheirUnecePosition(String code, String name, double lon, double lat) {
        UnLocode record = unLocodes().getByCode(code);
        assertThat(record.name()).isEqualTo(name);
        assertThat(record.functions()).isEqualTo(EnumSet.of(LocationFunction.MULTIMODAL));
        assertThat(record.coordinateSource()).isEqualTo("UNECE");

        ResolvedLocation resolved = TradeRoutes.locate(code);
        assertThat(resolved.source()).isEqualTo("unlocode");
        assertThat(resolved.coordinate()).isEqualTo(new Coordinate(lon, lat));
    }

    @Test
    void locateKeepsExistingCodesAsTheyWere() {
        // Bloemfontein was in the original import and keeps its UNECE position.
        assertThat(TradeRoutes.locate("ZABFN").coordinate()).isEqualTo(new Coordinate(26.2, -29.13333));
    }

    @ParameterizedTest
    @CsvSource({
            "NOKRN, Kristiansund", // 2025-1 adds NOKRN as Kroderen, 300 km inland.
            "NLWIL, Willemstad",   // 2025-1 adds NLWIL as Wilp.
            "UASVP, Sevastopol"
    })
    void locateLeavesPortListCodesToThePortList(String code, String portName) {
        assertThat(unLocodes().getByCode(code)).as("the merge does not add codes the port list uses").isNull();

        ResolvedLocation resolved = TradeRoutes.locate(code);
        assertThat(resolved.source()).isEqualTo("ports");
        assertThat(resolved.name()).startsWith(portName);
    }

    @ParameterizedTest
    @CsvSource({
            "CZBYT, 17.19, 49.54", // Bystrocice, published at 171.33E.
            "LKHET, 80.48, 7.51",  // Hettimulla, Sri Lanka, published at 80.08W.
            "LKWLP, 79.89, 6.94"   // Wellampitiya, published at 0,0.
    })
    void addedCodesWithACoordinateFarOutsideTheirCountryIgnoreTheUneceValue(String code, double lon, double lat) {
        // The merge held the UNECE coordinate back; the added-code fill places the code from its GeoNames link.
        UnLocode record = unLocodes().getByCode(code);
        assertThat(record.coordinateSource()).startsWith("Added fill, GeoNames ");
        assertThat(Haversine.distanceKm(record.coordinate(), new Coordinate(lon, lat))).isLessThan(2.0);
    }
}
