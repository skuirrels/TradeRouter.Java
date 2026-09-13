package io.github.skuirrels.traderouter.locations;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Lookup over the embedded UN/LOCODE list. Codes are matched case-insensitively.
 */
public final class UnLocodeDatabase {
    private final Map<String, UnLocode> byCode;
    private final int countWithCoordinates;

    /** Initializes a database over the given entries. Later duplicates replace earlier ones. */
    public UnLocodeDatabase(Iterable<UnLocode> entries) {
        Objects.requireNonNull(entries, "entries");
        byCode = new HashMap<>();
        int withCoordinates = 0;
        for (UnLocode entry : entries) {
            byCode.put(normalize(entry.code()), entry);
            if (entry.hasCoordinate()) {
                withCoordinates++;
            }
        }
        countWithCoordinates = withCoordinates;
    }

    /** Number of entries. */
    public int count() {
        return byCode.size();
    }

    /** Number of entries that carry coordinates. */
    public int countWithCoordinates() {
        return countWithCoordinates;
    }

    /** Looks up a code, case-insensitively, returning null when absent. */
    public UnLocode getByCode(String code) {
        Objects.requireNonNull(code, "code");
        return byCode.get(normalize(code));
    }

    private static String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
