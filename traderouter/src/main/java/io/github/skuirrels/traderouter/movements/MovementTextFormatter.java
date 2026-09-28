package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.passages.Passage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link MovementResult} as the fixed-width text report returned by {@link MovementResult#toText()}.
 */
final class MovementTextFormatter {
    private MovementTextFormatter() {
    }

    // The Basis column fits the longest basis, "teu_average_weight", with a space before it.
    static String format(MovementResult movement, boolean includeExplanations) {
        StringBuilder output = new StringBuilder();
        appendLine(output, String.format(Locale.ROOT,
                "%-4s%-10s%-6s%-7s%-7s%9s %20s%24s%16s%16s%12s%20s  Choke points",
                "Leg", "Kind", "Mode", "From", "To", "Distance", "Distance basis", "Modelled transit time", "CO2e rate",
                "CO2e per tonne", "CO2e total", "Basis"));
        appendLine(output, String.format(Locale.ROOT,
                "%-34s%9s %20s%24s%16s%16s%12s",
                "", "", "", "hours", "g per t-km", "kg per t cargo", "kg"));

        for (LegResult leg : movement.legs()) {
            appendLine(output, String.format(Locale.ROOT,
                    "%-4d%-10s%-6s%-7s%-7s%9s %s%20s%24s%16s%16s%12s%20s  %s",
                    leg.sequence(),
                    leg.leg().kind().displayName(),
                    leg.leg().mode().displayName(),
                    leg.from().label(),
                    leg.to().label(),
                    n0(leg.length()),
                    movement.units(),
                    orEmpty(leg.feature().properties().getDistanceBasis()),
                    n1(leg.transitHours()),
                    n1(leg.co2eGramsPerTonneKm()),
                    n1(leg.co2eKgPerTonne()),
                    formatNullable(leg.co2eKg()),
                    orEmpty(leg.co2eBasis()),
                    formatPassages(leg.feature().properties().getTraversedPassages())));
        }

        appendLine(output, String.format(Locale.ROOT,
                "%-34s%9s %s%20s%24s%16s%16s%12s%20s  %s",
                "Total",
                n0(movement.totalLength()),
                movement.units(),
                "",
                n1(movement.totalTransitHours()),
                "",
                n1(movement.totalCo2eKgPerTonne()),
                formatNullable(movement.totalCo2eKg()),
                "",
                formatCargo(movement)));
        appendLine(output, String.format(Locale.ROOT,
                "Modelled minimum = %s h travel + %s h sea operations + %s h port handling + %s h connections = %s h (%s days)",
                n1(movement.totalDurationHours()),
                n1(movement.totalOperationalAllowanceHours()),
                n0(movement.totalPortHours()),
                n0(movement.totalConnectionHours()),
                n1(movement.totalTransitHours()),
                n1(movement.totalTransitHours() / 24.0)));

        if (includeExplanations) {
            appendLine(output, formatDistanceBasisExplanation(movement));
            appendLine(output, "Timing         = planning lower bound from configured assumptions; excludes carrier schedules, customs and disruption");
            appendLine(output, "CO2e rate      = grams of CO2e emitted moving 1 tonne 1 km (configured factor for the mode)");
            appendLine(output, "CO2e per tonne = rate × leg distance: kg of CO2e for each tonne of cargo carried over the leg");
            appendLine(output, formatTotalExplanation(movement));
        }

        return output.toString().stripTrailing();
    }

    /** Formats a number with thousands separators and no decimals, as the .NET "N0" format does. */
    static String n0(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    /** Formats a number with thousands separators and one decimal, as the .NET "N1" format does. */
    static String n1(double value) {
        return String.format(Locale.ROOT, "%,.1f", value);
    }

    private static String formatPassages(List<String> passages) {
        if (passages == null || passages.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>(passages.size());
        for (String passage : passages) {
            names.add(Passage.getDisplayName(passage));
        }
        return String.join(", ", names);
    }

    private static String formatDistanceBasisExplanation(MovementResult movement) {
        String network = "Distance basis = maritime_network/road_network are network routes";
        String calculated = "; circuity_estimate is a planning estimate; great_circle is straight-line";
        boolean hasExternallyProvidedRoute = false;
        for (LegResult leg : movement.legs()) {
            if ("supplied".equals(leg.feature().properties().getDistanceBasis())) {
                hasExternallyProvidedRoute = true;
                break;
            }
        }

        return hasExternallyProvidedRoute
                ? network + "; supplied is an externally provided route result" + calculated
                : network + calculated;
    }

    private static String formatCargo(MovementResult movement) {
        String tonnes = movement.cargoTonnes() != null
                ? "for " + n0(movement.cargoTonnes()) + " t of cargo"
                : "";
        String teu = movement.cargoTeu() != null
                ? (tonnes.isEmpty() ? "for" : "in") + " " + n0(movement.cargoTeu()) + " TEU"
                : "";
        List<String> parts = new ArrayList<>(2);
        if (!tonnes.isEmpty()) {
            parts.add(tonnes);
        }
        if (!teu.isEmpty()) {
            parts.add(teu);
        }
        return String.join(" ", parts);
    }

    private static String formatNullable(Double value) {
        return value == null ? "" : n0(value);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String formatTotalExplanation(MovementResult movement) {
        if (movement.totalCo2eKg() == null) {
            return "CO2e total     = not calculated because cargo weight or TEU count was not supplied";
        }

        if (movement.cargoTeu() == null) {
            return "CO2e total     = kg of CO2e for this shipment: CO2e per tonne × cargo weight";
        }

        Double seaRate = null;
        for (LegResult leg : movement.legs()) {
            if (leg.leg().mode() == TransportMode.SEA) {
                seaRate = leg.feature().properties().getCo2eGramsPerTeuKm();
                break;
            }
        }
        String rate = seaRate != null ? n0(seaRate) : "the configured";
        return "CO2e total     = kg of CO2e for this shipment: " + rate
                + " g per TEU-km on sea legs, the greater of cargo weight and 10 t per TEU on road and rail legs, cargo weight on air legs";
    }

    private static void appendLine(StringBuilder output, String line) {
        output.append(line).append('\n');
    }
}
