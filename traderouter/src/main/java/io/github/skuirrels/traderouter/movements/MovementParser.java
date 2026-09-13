package io.github.skuirrels.traderouter.movements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses leg lines in the form
 * {@code [Pickup|Delivery] [from] [port|place|airport|station|terminal|depot] CODE to [...] CODE MODE}.
 * Mode and kind words are validated against {@link TransportMode} and {@link LegKind}.
 */
public final class MovementParser {
    private static final Pattern LEG_LINE = Pattern.compile(
            "^\\s*(?:(?<kind>[a-z]+)\\s+)??(?:from\\s+)?(?:(?<fromKind>port|place|airport|station|terminal|depot)\\s+)?"
                    + "(?<from>[A-Z]{2}[A-Z0-9]{3})\\s+to\\s+(?:(?<toKind>port|place|airport|station|terminal|depot)\\s+)?"
                    + "(?<to>[A-Z]{2}[A-Z0-9]{3})\\s+(?<mode>[a-z]+)\\s*\\.?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final String EXPECTED_FORM =
            "[Pickup|Delivery] [port|place|airport|station|terminal|depot] CODE to [port|place|airport|...] CODE Sea|Road|Rail|Air";

    private MovementParser() {
    }

    /**
     * Parses one leg per non-blank line. Throws {@link MovementFormatException} naming the first line, by its
     * position in the input text, that does not parse.
     */
    public static List<MovementLeg> parse(String legsText) {
        Objects.requireNonNull(legsText, "legsText");

        List<MovementLeg> legs = new ArrayList<>();
        String[] lines = legsText.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = stripCarriageReturn(lines[i]).trim();
            if (line.isEmpty()) {
                continue;
            }

            legs.add(parseLine(line, i + 1));
        }

        if (legs.isEmpty()) {
            throw new MovementFormatException("No legs found.");
        }

        return legs;
    }

    /** Parses a single leg line, returning empty when it is not a recognised leg. */
    public static Optional<MovementLeg> tryParseLine(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parseLine(line.trim(), 1));
        } catch (MovementFormatException e) {
            return Optional.empty();
        }
    }

    private static MovementLeg parseLine(String line, int lineNumber) {
        Matcher match = LEG_LINE.matcher(line);
        if (!match.matches()) {
            throw new MovementFormatException(
                    "Line " + lineNumber + " is not a recognised leg: \"" + line + "\". Expected \"" + EXPECTED_FORM + "\".");
        }

        LegKind kind = LegKind.MAIN;
        String kindText = match.group("kind");
        if (kindText != null) {
            kind = LegKind.tryParse(kindText).orElseThrow(() -> new MovementFormatException(
                    "Line " + lineNumber + ": unknown leg kind \"" + kindText + "\". Expected Pickup, Delivery or Main."));
        }

        String modeText = match.group("mode");
        TransportMode mode = TransportMode.tryParse(modeText).orElseThrow(() -> new MovementFormatException(
                "Line " + lineNumber + ": unknown transport mode \"" + modeText + "\". Expected Sea, Road, Rail or Air."));

        return new MovementLeg(
                match.group("from"),
                match.group("to"),
                mode,
                kind,
                parseWaypointKind(match.group("fromKind")),
                parseWaypointKind(match.group("toKind")));
    }

    private static WaypointKind parseWaypointKind(String value) {
        if (value == null) {
            return WaypointKind.UNSPECIFIED;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "" -> WaypointKind.UNSPECIFIED;
            case "place" -> WaypointKind.PLACE;
            case "port" -> WaypointKind.PORT;
            case "airport" -> WaypointKind.AIRPORT;
            case "station" -> WaypointKind.STATION;
            case "terminal" -> WaypointKind.TERMINAL;
            case "depot" -> WaypointKind.DEPOT;
            default -> throw new IllegalStateException("Unreachable waypoint kind '" + value + "'.");
        };
    }

    private static String stripCarriageReturn(String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == '\r') {
            end--;
        }
        return line.substring(0, end);
    }
}
