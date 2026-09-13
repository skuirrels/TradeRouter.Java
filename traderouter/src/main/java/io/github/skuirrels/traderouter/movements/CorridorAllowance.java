package io.github.skuirrels.traderouter.movements;

/**
 * The trade corridor chosen for a sea leg and the fraction of travelling time added for it.
 *
 * @param corridor corridor label, for example "asia-north-europe", "default" or "override"
 * @param fraction fraction of sea travelling time added as the operational allowance
 */
public record CorridorAllowance(String corridor, double fraction) {
}
