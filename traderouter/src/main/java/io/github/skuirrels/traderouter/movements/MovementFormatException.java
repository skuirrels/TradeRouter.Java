package io.github.skuirrels.traderouter.movements;

/**
 * Thrown by {@link MovementParser} when a leg line does not match the expected form.
 */
public final class MovementFormatException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message naming the offending line. */
    public MovementFormatException(String message) {
        super(message);
    }
}
