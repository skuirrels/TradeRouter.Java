package io.github.skuirrels.traderouter.seaareas;

/**
 * One passage of a route through a named sea area.
 *
 * @param id       sea-area identifier, one of the {@link SeaArea} constants. JSON: {@code id}
 * @param name     English display name. JSON: {@code name}
 * @param wikidata Wikidata item, as a cross-reference only. JSON: {@code wikidata}
 * @param enterKm  kilometres from the start of the route where it enters the area, whatever the route's units.
 *                 JSON: {@code enter_km}
 * @param exitKm   kilometres from the start of the route where it leaves the area, whatever the route's units.
 *                 JSON: {@code exit_km}
 */
public record SeaAreaTraversal(String id, String name, String wikidata, double enterKm, double exitKm) {
}
