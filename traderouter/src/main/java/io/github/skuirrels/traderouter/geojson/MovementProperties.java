package io.github.skuirrels.traderouter.geojson;

/**
 * Totals for a multi-leg movement, written as the foreign {@code properties} member of the feature collection.
 */
public final class MovementProperties {
    private double totalLength;
    private String units = "km";
    private double totalDurationHours;
    private double totalPortHours;
    private double totalOperationalAllowanceHours;
    private double totalConnectionHours;
    private double totalTransitHours;
    private int legCount;
    private double totalCo2eKgPerTonne;
    private Double cargoTonnes;
    private Double cargoTeu;
    private Double totalCo2eKg;

    /** Sum of leg lengths in {@link #getUnits()}. */
    public double getTotalLength() {
        return totalLength;
    }

    /** Sets the total length. */
    public MovementProperties setTotalLength(double totalLength) {
        this.totalLength = totalLength;
        return this;
    }

    /** Unit identifier. */
    public String getUnits() {
        return units;
    }

    /** Sets the unit identifier. */
    public MovementProperties setUnits(String units) {
        this.units = units;
        return this;
    }

    /** Sum of leg travelling durations in hours. */
    public double getTotalDurationHours() {
        return totalDurationHours;
    }

    /** Sets the total travelling hours. */
    public MovementProperties setTotalDurationHours(double totalDurationHours) {
        this.totalDurationHours = totalDurationHours;
        return this;
    }

    /** Sum of port hours across sea legs. */
    public double getTotalPortHours() {
        return totalPortHours;
    }

    /** Sets the total port hours. */
    public MovementProperties setTotalPortHours(double totalPortHours) {
        this.totalPortHours = totalPortHours;
        return this;
    }

    /** Sum of sea-service operational allowances. */
    public double getTotalOperationalAllowanceHours() {
        return totalOperationalAllowanceHours;
    }

    /** Sets the total operational allowance hours. */
    public MovementProperties setTotalOperationalAllowanceHours(double totalOperationalAllowanceHours) {
        this.totalOperationalAllowanceHours = totalOperationalAllowanceHours;
        return this;
    }

    /** Sum of transshipment connection hours. */
    public double getTotalConnectionHours() {
        return totalConnectionHours;
    }

    /** Sets the total connection hours. */
    public MovementProperties setTotalConnectionHours(double totalConnectionHours) {
        this.totalConnectionHours = totalConnectionHours;
        return this;
    }

    /** Modelled minimum hours across all legs. */
    public double getTotalTransitHours() {
        return totalTransitHours;
    }

    /** Sets the total transit hours. */
    public MovementProperties setTotalTransitHours(double totalTransitHours) {
        this.totalTransitHours = totalTransitHours;
        return this;
    }

    /** Number of legs. */
    public int getLegCount() {
        return legCount;
    }

    /** Sets the number of legs. */
    public MovementProperties setLegCount(int legCount) {
        this.legCount = legCount;
        return this;
    }

    /** Sum of leg CO2e per tonne of cargo, in kilograms. */
    public double getTotalCo2eKgPerTonne() {
        return totalCo2eKgPerTonne;
    }

    /** Sets the total CO2e per tonne. */
    public MovementProperties setTotalCo2eKgPerTonne(double totalCo2eKgPerTonne) {
        this.totalCo2eKgPerTonne = totalCo2eKgPerTonne;
        return this;
    }

    /** Cargo weight in tonnes, when stated, or null. */
    public Double getCargoTonnes() {
        return cargoTonnes;
    }

    /** Sets the cargo weight. */
    public MovementProperties setCargoTonnes(Double cargoTonnes) {
        this.cargoTonnes = cargoTonnes;
        return this;
    }

    /** Container count in TEU, when stated, or null. */
    public Double getCargoTeu() {
        return cargoTeu;
    }

    /** Sets the container count. */
    public MovementProperties setCargoTeu(Double cargoTeu) {
        this.cargoTeu = cargoTeu;
        return this;
    }

    /** Sum of leg CO2e in kilograms when a cargo weight or TEU count is stated, or null. */
    public Double getTotalCo2eKg() {
        return totalCo2eKg;
    }

    /** Sets the total CO2e in kilograms. */
    public MovementProperties setTotalCo2eKg(Double totalCo2eKg) {
        this.totalCo2eKg = totalCo2eKg;
        return this;
    }
}
