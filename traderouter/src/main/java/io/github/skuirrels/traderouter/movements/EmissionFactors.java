package io.github.skuirrels.traderouter.movements;

/**
 * Well-to-wheel CO2e emission intensity per transport mode, in grams of CO2e per tonne-kilometre. Defaults are
 * the GLEC Framework v3.2 default values (Smart Freight Centre, October 2025, Module 2), which ISO 14083 builds on.
 * Override any property to use carrier-specific or newer factors.
 */
public final class EmissionFactors {
    private double seaGramsPerTeuKm = 71.7;
    private double averageTonnesPerTeu = 10.0;
    private double seaGramsPerTonneKm = 7.17;
    private double roadGramsPerTonneKm = 125.0;
    private double railGramsPerTonneKm = 30.7;
    private double railNorthAmericaGramsPerTonneKm = 16.1;
    private double airShortHaulMaxKm = 1500.0;
    private double airFreighterShortHaulGramsPerTonneKm = 1516.0;
    private double airFreighterLongHaulGramsPerTonneKm = 608.0;
    private double airBellyShortHaulGramsPerTonneKm = 1239.0;
    private double airBellyLongHaulGramsPerTonneKm = 936.0;
    private double airUnknownShortHaulGramsPerTonneKm = 1363.0;
    private double airUnknownLongHaulGramsPerTonneKm = 788.0;

    /** A fresh instance carrying the GLEC default values. */
    public static EmissionFactors glecDefaults() {
        return new EmissionFactors();
    }

    /**
     * Deep-sea container shipping per container. GLEC v3.2 Table 18, industry average dry container, end-user
     * intensity, 71.7 g CO2e per TEU-km WTW. Used for sea legs when the request states a TEU count.
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

    /**
     * Road haulage. GLEC v3.2 Europe starting value for an HGV over 20 t GVW, 125 g CO2e per tonne-km WTW, including
     * GLEC's +5% distance adjustment.
     */
    public double getRoadGramsPerTonneKm() {
        return roadGramsPerTonneKm;
    }

    /** Sets the road rate. */
    public EmissionFactors setRoadGramsPerTonneKm(double value) {
        this.roadGramsPerTonneKm = value;
        return this;
    }

    /**
     * Rail freight outside North America. GLEC v3.2 Table 4, European diesel traction, average/mixed load,
     * 30.7 g CO2e per tonne-km WTW.
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
     * {@link MovementRequest#getRailNetworkSnapKm()} of the embedded North American rail network. GLEC v3.2
     * Module 2, North America, US average diesel, 16.1 g CO2e per tonne-km WTW, derived from Class I railroad fuel
     * reporting.
     */
    public double getRailNorthAmericaGramsPerTonneKm() {
        return railNorthAmericaGramsPerTonneKm;
    }

    /** Sets the North American rail rate. */
    public EmissionFactors setRailNorthAmericaGramsPerTonneKm(double value) {
        this.railNorthAmericaGramsPerTonneKm = value;
        return this;
    }

    /**
     * Great-circle length below which an air leg is short-haul, 1,500 km as in GLEC v3.2 Table 1 (SBTi and ISO 14083
     * definitions). The air factors already include GLEC's +95 km distance adjustment, so they apply to the
     * great-circle distance of the leg as it stands.
     */
    public double getAirShortHaulMaxKm() {
        return airShortHaulMaxKm;
    }

    /** Sets the short-haul air threshold in kilometres. */
    public EmissionFactors setAirShortHaulMaxKm(double value) {
        this.airShortHaulMaxKm = value;
        return this;
    }

    /** Freighter, short-haul. GLEC v3.2 Table 1, 1,516 g CO2e per tonne-km WTW. */
    public double getAirFreighterShortHaulGramsPerTonneKm() {
        return airFreighterShortHaulGramsPerTonneKm;
    }

    /** Sets the short-haul freighter rate. */
    public EmissionFactors setAirFreighterShortHaulGramsPerTonneKm(double value) {
        this.airFreighterShortHaulGramsPerTonneKm = value;
        return this;
    }

    /** Freighter, long-haul. GLEC v3.2 Table 1, 608 g CO2e per tonne-km WTW. */
    public double getAirFreighterLongHaulGramsPerTonneKm() {
        return airFreighterLongHaulGramsPerTonneKm;
    }

    /** Sets the long-haul freighter rate. */
    public EmissionFactors setAirFreighterLongHaulGramsPerTonneKm(double value) {
        this.airFreighterLongHaulGramsPerTonneKm = value;
        return this;
    }

    /** Belly freight on a passenger aircraft, short-haul. GLEC v3.2 Table 1, 1,239 g CO2e per tonne-km WTW. */
    public double getAirBellyShortHaulGramsPerTonneKm() {
        return airBellyShortHaulGramsPerTonneKm;
    }

    /** Sets the short-haul belly freight rate. */
    public EmissionFactors setAirBellyShortHaulGramsPerTonneKm(double value) {
        this.airBellyShortHaulGramsPerTonneKm = value;
        return this;
    }

    /** Belly freight on a passenger aircraft, long-haul. GLEC v3.2 Table 1, 936 g CO2e per tonne-km WTW. */
    public double getAirBellyLongHaulGramsPerTonneKm() {
        return airBellyLongHaulGramsPerTonneKm;
    }

    /** Sets the long-haul belly freight rate. */
    public EmissionFactors setAirBellyLongHaulGramsPerTonneKm(double value) {
        this.airBellyLongHaulGramsPerTonneKm = value;
        return this;
    }

    /** Air freight on an unknown service, short-haul. GLEC v3.2 Table 1, 1,363 g CO2e per tonne-km WTW. */
    public double getAirUnknownShortHaulGramsPerTonneKm() {
        return airUnknownShortHaulGramsPerTonneKm;
    }

    /** Sets the short-haul unknown-service air rate. */
    public EmissionFactors setAirUnknownShortHaulGramsPerTonneKm(double value) {
        this.airUnknownShortHaulGramsPerTonneKm = value;
        return this;
    }

    /** Air freight on an unknown service, long-haul. GLEC v3.2 Table 1, 788 g CO2e per tonne-km WTW. */
    public double getAirUnknownLongHaulGramsPerTonneKm() {
        return airUnknownLongHaulGramsPerTonneKm;
    }

    /** Sets the long-haul unknown-service air rate. */
    public EmissionFactors setAirUnknownLongHaulGramsPerTonneKm(double value) {
        this.airUnknownLongHaulGramsPerTonneKm = value;
        return this;
    }

    /**
     * Returns the factor for a leg of the given mode and length, taking an air leg's service as unknown. See
     * {@link #gramsPerTonneKm(TransportMode, double, AirCargoService)}.
     */
    public double gramsPerTonneKm(TransportMode mode, double distanceKm) {
        return gramsPerTonneKm(mode, distanceKm, AirCargoService.UNKNOWN);
    }

    /**
     * Returns the factor for a leg of the given mode and length. Air uses the GLEC short- and long-haul bands for
     * the service. Rail returns {@link #getRailGramsPerTonneKm()}; a movement charges North American rail legs at
     * {@link #getRailNorthAmericaGramsPerTonneKm()} instead.
     */
    public double gramsPerTonneKm(TransportMode mode, double distanceKm, AirCargoService airService) {
        return switch (mode) {
            case SEA -> seaGramsPerTonneKm;
            case ROAD -> roadGramsPerTonneKm;
            case RAIL -> railGramsPerTonneKm;
            case AIR -> {
                boolean shortHaul = distanceKm < airShortHaulMaxKm;
                yield switch (airService == null ? AirCargoService.UNKNOWN : airService) {
                    case FREIGHTER -> shortHaul ? airFreighterShortHaulGramsPerTonneKm : airFreighterLongHaulGramsPerTonneKm;
                    case BELLY -> shortHaul ? airBellyShortHaulGramsPerTonneKm : airBellyLongHaulGramsPerTonneKm;
                    case UNKNOWN -> shortHaul ? airUnknownShortHaulGramsPerTonneKm : airUnknownLongHaulGramsPerTonneKm;
                };
            }
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
        validateNonNegative(airShortHaulMaxKm, "airShortHaulMaxKm");
        validateNonNegative(airFreighterShortHaulGramsPerTonneKm, "airFreighterShortHaulGramsPerTonneKm");
        validateNonNegative(airFreighterLongHaulGramsPerTonneKm, "airFreighterLongHaulGramsPerTonneKm");
        validateNonNegative(airBellyShortHaulGramsPerTonneKm, "airBellyShortHaulGramsPerTonneKm");
        validateNonNegative(airBellyLongHaulGramsPerTonneKm, "airBellyLongHaulGramsPerTonneKm");
        validateNonNegative(airUnknownShortHaulGramsPerTonneKm, "airUnknownShortHaulGramsPerTonneKm");
        validateNonNegative(airUnknownLongHaulGramsPerTonneKm, "airUnknownLongHaulGramsPerTonneKm");
    }

    private static void validateNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(
                    "Emission factors must be finite and non-negative. " + name + " was " + value + ".");
        }
    }
}
