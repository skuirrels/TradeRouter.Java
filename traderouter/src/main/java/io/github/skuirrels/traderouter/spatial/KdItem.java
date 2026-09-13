package io.github.skuirrels.traderouter.spatial;

import io.github.skuirrels.traderouter.common.Coordinate;

/**
 * A coordinate and its payload, as stored in and returned from a {@link KdTree}.
 *
 * @param point the geographic position
 * @param value the payload
 * @param <T>   payload type
 */
public record KdItem<T>(Coordinate point, T value) {
}
