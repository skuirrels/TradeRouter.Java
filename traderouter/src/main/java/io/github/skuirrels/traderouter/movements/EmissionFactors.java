package io.github.skuirrels.traderouter.movements;

/**
 * Well-to-wheel CO2e emission intensity per transport mode, in grams of CO2e per tonne-kilometre. Defaults are
 * the GLEC Framework default values (Smart Freight Centre, GLEC Framework v2, July 2022 edition, Module 2),
 * which ISO 14083 builds on. Override any property to use carrier-specific or newer factors.
 */
public final class EmissionFactors {
    private double seaGramsPerTeuKm = 76.0;
    private double averageTonnesPerTeu = 10.0;
    private double seaGramsPerTonneKm = 7.6;
    private double roadGramsPerTonneKm = 92.0;
    private double railGramsPerTonneKm = 28.0;
    private double railNorthAmericaGramsPerTonneKm = 16.0;
    private double airShortHaulGramsPerTonneKm = 1130.0;
    private double airMediumHaulGramsPerTonneKm = 700.0;
    private double airLongHaulGramsPerTonneKm = 630.0;

    /** A fresh instance carrying the GLEC default values. */
    public static EmissionFactors glecDefaults() {
        return new EmissionFactors();
    }

    /**
     * Deep-sea container shipping per container. GLEC Table 46, industry average dry container, 76 g CO2e per
     * TEU-km WTW. Used for sea legs when the request states a TEU count.
     */
    public double getSeaGramsPerTeuKm() {
        return seaGramsPerTeuKm;
    }

    /** Sets the per-TEU sea rate. */
    public EmissionFactors setSeaGramsPerTeuKm(double value) {
        this.seaGramsPerTeuKm = value;
        return this;
    }

    /** GLEC average cargo weight per TEU, 10 tonnes, used to convert between the two sea bases. */
    public double getAverageTonnesPerTeu() {
        return averageTonnesPerTeu;
    }

    /** Sets the average tonnes per TEU. */
    public EmissionFactors setAverageTonnesPerTeu(double value) {
        this.averageTonnesPerTeu = value;
        return this;
    }

    /**
     * Deep-sea container shipping per tonne: {@link #getSeaGramsPerTeuKm()} divided by
     * {@link #getAverageTonnesPerTeu()}. Used for sea legs when only a cargo weight is known.
     */
    public double getSeaGramsPerTonneKm() {
        return seaGramsPerTonneKm;
    }

    /** Sets the per-tonne sea rate. */
    public EmissionFactors setSeaGramsPerTonneKm(double value) {
        this.seaGramsPerTonneKm = value;
        return this;
    }

    /** Road haulage. GLEC Europe starting value for an HGV over 20 t GVW, 92 g CO2e per tonne-km WTW. */
    public double getRoadGramsPerTonneKm() {
        return roadGramsPerTonneKm;
    }

    /** Sets the road rate. */
    public EmissionFactors setRoadGramsPerTonneKm(double value) {
        this.roadGramsPerTonneKm = value;
        return this;
    }

    /**
     * Rail freight outside North America. GLEC Table 38, European diesel traction, average/mixed load,
     * 28 g CO2e per tonne-km WTW.
     */
    public double getRailGramsPerTonneKm() {
        return railGramsPerTonneKm;
    }

    /** Sets the rail rate. */
    public EmissionFactors setRailGramsPerTonneKm(double value) {
        this.railGramsPerTonneKm = value;
        return this;
    }

    /**
     * Rail freight in North America: a rail leg whose ends both lie within
     * {@link MovementRequest#getRailNetworkSnapKm()} of the embedded North American rail network. GLEC Module 2,
     * North America, US average diesel, 16 g CO2e per tonne-km WTW, derived from Class I railroad fuel reporting.
     */
    public double getRailNorthAmericaGramsPerTonneKm() {
        return railNorthAmericaGramsPerTonneKm;
    }

    /** Sets the North American rail rate. */
    public EmissionFactors setRailNorthAmericaGramsPerTonneKm(double value) {
        this.railNorthAmericaGramsPerTonneKm = value;
        return this;
    }

    /** Air freight under 1,000 km. GLEC Table 35, ICAO/IATA RP1678 basis, aircraft type unknown, 1,130 g CO2e per tonne-km WTW. */
    public double getAirShortHaulGramsPerTonneKm() {
        return airShortHaulGramsPerTonneKm;
    }

    /** Sets the short-haul air rate. */
    public EmissionFactors setAirShortHaulGramsPerTonneKm(double value) {
        this.airShortHaulGramsPerTonneKm = value;
        return this;
    }

    /** Air freight from 1,000 to 3,700 km. GLEC Table 35, aircraft type unknown, 700 g CO2e per tonne-km WTW. */
    public double getAirMediumHaulGramsPerTonneKm() {
        return airMediumHaulGramsPerTonneKm;
    }

    /** Sets the medium-haul air rate. */
    public EmissionFactors setAirMediumHaulGramsPerTonneKm(double value) {
        this.airMediumHaulGramsPerTonneKm = value;
        return this;
    }

    /** Air freight over 3,700 km. GLEC Table 35, aircraft type unknown, 630 g CO2e per tonne-km WTW. */
    public double getAirLongHaulGramsPerTonneKm() {
        return airLongHaulGramsPerTonneKm;
    }

    /** Sets the long-haul air rate. */
    public EmissionFactors setAirLongHaulGramsPerTonneKm(double value) {
        this.airLongHaulGramsPerTonneKm = value;
        return this;
    }

    /**
     * Returns the factor for a leg of the given mode and length. Air uses the GLEC distance bands. Rail returns
     * {@link #getRailGramsPerTonneKm()}; a movement charges North American rail legs at
     * {@link #getRailNorthAmericaGramsPerTonneKm()} instead.
     */
    public double gramsPerTonneKm(TransportMode mode, double distanceKm) {
        return switch (mode) {
            case SEA -> seaGramsPerTonneKm;
            case ROAD -> roadGramsPerTonneKm;
            case RAIL -> railGramsPerTonneKm;
            case AIR -> distanceKm < 1000.0
                    ? airShortHaulGramsPerTonneKm
                    : distanceKm <= 3700.0 ? airMediumHaulGramsPerTonneKm : airLongHaulGramsPerTonneKm;
        };
    }

    /** Validates that every factor is finite and non-negative and the average TEU weight positive. */
    public void validate() {
        validateNonNegative(seaGramsPerTeuKm, "seaGramsPerTeuKm");
        if (!Double.isFinite(averageTonnesPerTeu) || averageTonnesPerTeu <= 0) {
            throw new IllegalArgumentException(
                    "Average tonnes per TEU must be finite and positive. Received: " + averageTonnesPerTeu);
        }
        validateNonNegative(seaGramsPerTonneKm, "seaGramsPerTonneKm");
        validateNonNegative(roadGramsPerTonneKm, "roadGramsPerTonneKm");
        validateNonNegative(railGramsPerTonneKm, "railGramsPerTonneKm");
        validateNonNegative(railNorthAmericaGramsPerTonneKm, "railNorthAmericaGramsPerTonneKm");
        validateNonNegative(airShortHaulGramsPerTonneKm, "airShortHaulGramsPerTonneKm");
        validateNonNegative(airMediumHaulGramsPerTonneKm, "airMediumHaulGramsPerTonneKm");
        validateNonNegative(airLongHaulGramsPerTonneKm, "airLongHaulGramsPerTonneKm");
    }

    private static void validateNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(
                    "Emission factors must be finite and non-negative. " + name + " was " + value + ".");
        }
    }
}
