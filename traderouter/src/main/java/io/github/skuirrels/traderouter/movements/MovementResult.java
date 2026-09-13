package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeatureCollection;
import io.github.skuirrels.traderouter.geojson.MovementProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Routed result for a whole movement.
 */
public final class MovementResult {
    private final List<LegResult> legs;
    private final String units;
    private final double totalLength;
    private final double totalDurationHours;
    private final double totalPortHours;
    private final double totalOperationalAllowanceHours;
    private final double totalConnectionHours;
    private final double totalTransitHours;
    private final Map<TransportMode, Double> lengthByMode;
    private final double totalCo2eKgPerTonne;
    private final Double cargoTonnes;
    private final Double cargoTeu;
    private final Double totalCo2eKg;

    /** Creates a movement result and computes its totals. Instances are normally created by the engine. */
    public MovementResult(List<LegResult> legs, String units, Double cargoTonnes, Double cargoTeu) {
        this.legs = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(legs, "legs")));
        this.units = Objects.requireNonNull(units, "units");
        this.cargoTonnes = cargoTonnes;
        this.cargoTeu = cargoTeu;

        double totalLengthSum = 0.0;
        double totalHours = 0.0;
        double totalPort = 0.0;
        double totalOperationalAllowance = 0.0;
        double totalConnection = 0.0;
        double totalTransit = 0.0;
        double totalCo2ePerTonne = 0.0;
        double totalCo2e = 0.0;
        boolean anyAbsolute = false;
        Map<TransportMode, Double> byMode = new EnumMap<>(TransportMode.class);
        for (LegResult leg : this.legs) {
            totalLengthSum += leg.length();
            totalHours += leg.durationHours();
            totalPort += leg.portHours();
            totalOperationalAllowance += leg.operationalAllowanceHours();
            totalConnection += leg.connectionHours();
            totalTransit += leg.transitHours();
            totalCo2ePerTonne += leg.co2eKgPerTonne();
            if (leg.co2eKg() != null) {
                totalCo2e += leg.co2eKg();
                anyAbsolute = true;
            }
            byMode.merge(leg.leg().mode(), leg.length(), Double::sum);
        }

        this.totalLength = totalLengthSum;
        this.totalDurationHours = totalHours;
        this.totalPortHours = totalPort;
        this.totalOperationalAllowanceHours = totalOperationalAllowance;
        this.totalConnectionHours = totalConnection;
        this.totalTransitHours = totalTransit;
        this.lengthByMode = Collections.unmodifiableMap(byMode);
        this.totalCo2eKgPerTonne = totalCo2ePerTonne;
        this.totalCo2eKg = anyAbsolute ? totalCo2e : null;
    }

    /** Per-leg results in order. */
    public List<LegResult> legs() {
        return legs;
    }

    /** Unit identifier shared by every length in the result. */
    public String units() {
        return units;
    }

    /** Sum of leg lengths. */
    public double totalLength() {
        return totalLength;
    }

    /** Sum of leg travelling hours. */
    public double totalDurationHours() {
        return totalDurationHours;
    }

    /** Sum of port hours across sea legs. */
    public double totalPortHours() {
        return totalPortHours;
    }

    /** Sum of sea-service operational allowances. */
    public double totalOperationalAllowanceHours() {
        return totalOperationalAllowanceHours;
    }

    /** Sum of transshipment connection hours. */
    public double totalConnectionHours() {
        return totalConnectionHours;
    }

    /** Modelled minimum hours across all legs. */
    public double totalTransitHours() {
        return totalTransitHours;
    }

    /** Length per transport mode. */
    public Map<TransportMode, Double> lengthByMode() {
        return lengthByMode;
    }

    /** Sum of leg CO2e per tonne of cargo, in kilograms. */
    public double totalCo2eKgPerTonne() {
        return totalCo2eKgPerTonne;
    }

    /** Cargo weight in tonnes when stated on the request, or null. */
    public Double cargoTonnes() {
        return cargoTonnes;
    }

    /** Container count in TEU when stated on the request, or null. */
    public Double cargoTeu() {
        return cargoTeu;
    }

    /** Sum of leg CO2e in kilograms when a cargo weight or TEU count is stated, or null. */
    public Double totalCo2eKg() {
        return totalCo2eKg;
    }

    /** Builds a GeoJSON FeatureCollection with one feature per leg and the totals as foreign members. */
    public GeoJsonFeatureCollection toFeatureCollection() {
        List<GeoJsonFeature> features = new ArrayList<>(legs.size());
        for (LegResult leg : legs) {
            features.add(leg.feature());
        }

        MovementProperties properties = new MovementProperties()
                .setTotalLength(totalLength)
                .setUnits(units)
                .setTotalDurationHours(totalDurationHours)
                .setTotalPortHours(totalPortHours)
                .setTotalOperationalAllowanceHours(totalOperationalAllowanceHours)
                .setTotalConnectionHours(totalConnectionHours)
                .setTotalTransitHours(totalTransitHours)
                .setLegCount(legs.size())
                .setTotalCo2eKgPerTonne(totalCo2eKgPerTonne)
                .setCargoTonnes(cargoTonnes)
                .setCargoTeu(cargoTeu)
                .setTotalCo2eKg(totalCo2eKg);
        return new GeoJsonFeatureCollection(features, properties);
    }

    /** Serializes the movement as a compact GeoJSON FeatureCollection. */
    public String toJson() {
        return toJson(false);
    }

    /** Serializes the movement as a GeoJSON FeatureCollection, optionally indented. */
    public String toJson(boolean writeIndented) {
        return toFeatureCollection().toJson(writeIndented);
    }

    /**
     * Formats the complete movement as a human-readable text table with leg details, totals, transit time,
     * emissions, traversed choke points and short explanations of the emissions columns.
     */
    public String toText() {
        return toText(true);
    }

    /**
     * Formats the complete movement as a human-readable text table.
     *
     * @param includeExplanations whether to append short explanations of the emissions columns
     */
    public String toText(boolean includeExplanations) {
        return MovementTextFormatter.format(this, includeExplanations);
    }
}
