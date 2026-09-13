package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.geojson.TradeRouterProperties;

import java.util.Objects;

/**
 * Routed result for one leg of a movement.
 */
public final class LegResult {
    private final int sequence;
    private final MovementLeg leg;
    private final ResolvedLocation from;
    private final ResolvedLocation to;
    private final GeoJsonFeature feature;

    /** Creates a leg result. Instances are normally created by the engine. */
    public LegResult(int sequence, MovementLeg leg, ResolvedLocation from, ResolvedLocation to, GeoJsonFeature feature) {
        this.sequence = sequence;
        this.leg = Objects.requireNonNull(leg, "leg");
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.feature = Objects.requireNonNull(feature, "feature");
    }

    /** 1-based position of the leg in the movement. */
    public int sequence() {
        return sequence;
    }

    /** The leg as requested. */
    public MovementLeg leg() {
        return leg;
    }

    /** Resolved start location. */
    public ResolvedLocation from() {
        return from;
    }

    /** Resolved end location. */
    public ResolvedLocation to() {
        return to;
    }

    /** Route geometry and properties for the leg. */
    public GeoJsonFeature feature() {
        return feature;
    }

    private TradeRouterProperties properties() {
        return feature.properties();
    }

    /** Leg length in the movement's units. */
    public double length() {
        return properties().getLength();
    }

    /** Travelling time in hours: distance divided by the mode's speed. */
    public double durationHours() {
        return properties().getDurationHours();
    }

    /** Port time in hours: dwell at each end of a sea leg, zero for road, rail and air. */
    public double portHours() {
        return orZero(properties().getPortHours());
    }

    /** Normal sea-service operational allowance in hours, zero for non-sea legs. */
    public double operationalAllowanceHours() {
        return orZero(properties().getOperationalAllowanceHours());
    }

    /** Transshipment connection time before this leg in hours. */
    public double connectionHours() {
        return orZero(properties().getConnectionHours());
    }

    /** Modelled minimum time in hours, including all configured allowances. */
    public double transitHours() {
        Double transit = properties().getTransitHours();
        return transit != null ? transit : durationHours();
    }

    /** Well-to-wheel CO2e intensity applied, in grams per tonne-kilometre. */
    public double co2eGramsPerTonneKm() {
        return orZero(properties().getCo2eGramsPerTonneKm());
    }

    /** CO2e per tonne of cargo for this leg, in kilograms. */
    public double co2eKgPerTonne() {
        return orZero(properties().getCo2eKgPerTonne());
    }

    /** CO2e for this leg in kilograms when the movement states a cargo weight or TEU count, or null. */
    public Double co2eKg() {
        return properties().getCo2eKg();
    }

    /** Basis of {@link #co2eKg()}: "tonnes", "teu" or "teu_average_weight", or null. */
    public String co2eBasis() {
        return properties().getCo2eBasis();
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }
}
