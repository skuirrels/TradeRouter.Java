package io.github.skuirrels.traderouter.movements;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Formats estimator parameters in model identifiers the way the .NET {@code 0.#####} custom format does. */
final class ModelNames {
    private ModelNames() {
    }

    static String format(double value, int maximumDecimals) {
        return BigDecimal.valueOf(value)
                .setScale(maximumDecimals, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
