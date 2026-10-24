package io.github.skuirrels.traderouter.seaareas;

import io.github.skuirrels.traderouter.data.EmbeddedResources;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Named sea areas a route can report in {@code traversed_sea_areas}: seas, gulfs, straits and oceans drawn from
 * Natural Earth 1:50m marine polygons. Names, Wikidata cross-references and Natural Earth IDs are in
 * {@link #getCatalogue()}, loaded from the embedded selection.
 */
public final class SeaArea {
    private SeaArea() {
    }

    /** Red Sea. */
    public static final String RED_SEA = "red_sea";

    /** Gulf of Aden. */
    public static final String GULF_OF_ADEN = "gulf_of_aden";

    /** Arabian Sea. */
    public static final String ARABIAN_SEA = "arabian_sea";

    /** Gulf of Oman. */
    public static final String GULF_OF_OMAN = "gulf_of_oman";

    /** Persian Gulf. */
    public static final String PERSIAN_GULF = "persian_gulf";

    /** Laccadive Sea. */
    public static final String LACCADIVE_SEA = "laccadive_sea";

    /** Bay of Bengal. */
    public static final String BAY_OF_BENGAL = "bay_of_bengal";

    /** Andaman Sea. */
    public static final String ANDAMAN_SEA = "andaman_sea";

    /** Strait of Malacca. */
    public static final String STRAIT_OF_MALACCA = "strait_of_malacca";

    /** Strait of Singapore. */
    public static final String STRAIT_OF_SINGAPORE = "strait_of_singapore";

    /** Java Sea. */
    public static final String JAVA_SEA = "java_sea";

    /** South China Sea. */
    public static final String SOUTH_CHINA_SEA = "south_china_sea";

    /** Gulf of Thailand. */
    public static final String GULF_OF_THAILAND = "gulf_of_thailand";

    /** Taiwan Strait. */
    public static final String TAIWAN_STRAIT = "taiwan_strait";

    /** East China Sea. */
    public static final String EAST_CHINA_SEA = "east_china_sea";

    /** Yellow Sea. */
    public static final String YELLOW_SEA = "yellow_sea";

    /** Bo Hai. */
    public static final String BO_HAI = "bo_hai";

    /** Korea Strait. */
    public static final String KOREA_STRAIT = "korea_strait";

    /** Sea of Japan. */
    public static final String SEA_OF_JAPAN = "sea_of_japan";

    /** Philippine Sea. */
    public static final String PHILIPPINE_SEA = "philippine_sea";

    /** Mediterranean Sea. */
    public static final String MEDITERRANEAN_SEA = "mediterranean_sea";

    /** Aegean Sea. */
    public static final String AEGEAN_SEA = "aegean_sea";

    /** Black Sea. */
    public static final String BLACK_SEA = "black_sea";

    /** Strait of Gibraltar. */
    public static final String STRAIT_OF_GIBRALTAR = "strait_of_gibraltar";

    /** North Sea. */
    public static final String NORTH_SEA = "north_sea";

    /** English Channel. */
    public static final String ENGLISH_CHANNEL = "english_channel";

    /** Baltic Sea. */
    public static final String BALTIC_SEA = "baltic_sea";

    /** Bay of Biscay. */
    public static final String BAY_OF_BISCAY = "bay_of_biscay";

    /** Gulf of Guinea. */
    public static final String GULF_OF_GUINEA = "gulf_of_guinea";

    /** Caribbean Sea. */
    public static final String CARIBBEAN_SEA = "caribbean_sea";

    /** Gulf of Mexico. */
    public static final String GULF_OF_MEXICO = "gulf_of_mexico";

    /** Straits of Florida. */
    public static final String STRAITS_OF_FLORIDA = "straits_of_florida";

    /** Mozambique Channel. */
    public static final String MOZAMBIQUE_CHANNEL = "mozambique_channel";

    /** Coral Sea. */
    public static final String CORAL_SEA = "coral_sea";

    /** Tasman Sea. */
    public static final String TASMAN_SEA = "tasman_sea";

    /** North Atlantic Ocean. */
    public static final String NORTH_ATLANTIC_OCEAN = "north_atlantic_ocean";

    /** South Atlantic Ocean. */
    public static final String SOUTH_ATLANTIC_OCEAN = "south_atlantic_ocean";

    /** North Pacific Ocean. */
    public static final String NORTH_PACIFIC_OCEAN = "north_pacific_ocean";

    /** South Pacific Ocean. */
    public static final String SOUTH_PACIFIC_OCEAN = "south_pacific_ocean";

    /** Indian Ocean. */
    public static final String INDIAN_OCEAN = "indian_ocean";

    /** Arctic Ocean. */
    public static final String ARCTIC_OCEAN = "arctic_ocean";

    /** Southern Ocean. */
    public static final String SOUTHERN_OCEAN = "southern_ocean";

    /** Every recognised sea area, in the order of the embedded selection. */
    public static List<SeaAreaInfo> getCatalogue() {
        return Catalogue.AREAS;
    }

    /** Gets all recognised sea-area identifiers, matched case-insensitively. */
    public static Set<String> getValidSeaAreas() {
        return Catalogue.IDS;
    }

    /** Returns the catalogue entry for a recognised sea-area identifier, matched case-insensitively after trimming. */
    public static SeaAreaInfo get(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Sea area identifier cannot be null or blank.");
        }
        SeaAreaInfo area = Catalogue.BY_ID.get(id.trim());
        if (area == null) {
            throw new IllegalArgumentException("Unknown sea area identifier '" + id + "'.");
        }
        return area;
    }

    /** Loads the catalogue on first use. */
    private static final class Catalogue {
        static final List<SeaAreaInfo> AREAS = EmbeddedResources.loadSeaAreaCatalogue();
        static final Map<String, SeaAreaInfo> BY_ID;
        static final Set<String> IDS;

        static {
            Map<String, SeaAreaInfo> byId = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            Set<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (SeaAreaInfo area : AREAS) {
                byId.put(area.id(), area);
                ids.add(area.id());
            }
            BY_ID = Collections.unmodifiableMap(byId);
            IDS = Collections.unmodifiableSet(ids);
        }
    }
}
