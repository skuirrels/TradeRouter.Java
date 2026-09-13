package io.github.skuirrels.traderouter.ports;

/**
 * A combination of origin and destination port selected for a route.
 *
 * @param originPort port of loading
 * @param destPort   port of discharge
 */
public record PortPair(Port originPort, Port destPort) {
}
