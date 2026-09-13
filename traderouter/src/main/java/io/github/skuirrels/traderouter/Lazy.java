package io.github.skuirrels.traderouter;

import java.util.function.Supplier;

/**
 * Thread-safe lazily initialised value: the supplier runs at most once, on first access.
 */
final class Lazy<T> {
    private Supplier<T> supplier;
    private volatile T value;

    Lazy(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    static <T> Lazy<T> of(T value) {
        Lazy<T> lazy = new Lazy<>(null);
        lazy.value = value;
        return lazy;
    }

    T get() {
        T result = value;
        if (result == null) {
            synchronized (this) {
                result = value;
                if (result == null) {
                    result = supplier.get();
                    value = result;
                    supplier = null;
                }
            }
        }
        return result;
    }
}
