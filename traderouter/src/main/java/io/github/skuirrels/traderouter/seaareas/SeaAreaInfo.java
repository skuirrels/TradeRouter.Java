package io.github.skuirrels.traderouter.seaareas;

/**
 * Catalogue entry for a named sea area.
 *
 * @param id       lower snake_case identifier taken from the English name, such as "red_sea"
 * @param name     English display name, such as "Red Sea"
 * @param wikidata Wikidata item, as a cross-reference only: the North and South halves of an ocean share one item
 * @param neId     Natural Earth {@code ne_id} of the polygon in {@code ne_50m_geography_marine_polys}
 */
public record SeaAreaInfo(String id, String name, String wikidata, long neId) {
}
