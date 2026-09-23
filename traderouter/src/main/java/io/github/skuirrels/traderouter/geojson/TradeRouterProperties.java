package io.github.skuirrels.traderouter.geojson;

import io.github.skuirrels.traderouter.ports.Port;

import java.util.List;

/**
 * Properties associated with a calculated sea route GeoJSON feature. Nullable values are omitted from the JSON
 * output when null.
 */
public final class TradeRouterProperties {
    private double length;
    private String units = "km";
    private double durationHours;
    private String distanceBasis;
    private Double straightLineLength;
    private String distanceSource;
    private String routingProfile;
    private String routingDataVersion;
    private String durationBasis;
    private String geometryBasis;
    private String distanceWarning;
    private Port portOrigin;
    private Port portDest;
    private List<String> traversedPassages;
    private Integer leg;
    private String mode;
    private String kind;
    private String from;
    private String to;
    private Double portHours;
    private Double operationalAllowanceHours;
    private Double operationalAllowanceFraction;
    private String operationalAllowanceCorridor;
    private Double connectionHours;
    private Double transitHours;
    private Double co2eGramsPerTonneKm;
    private Double co2eKgPerTonne;
    private Double co2eGramsPerTeuKm;
    private Double co2eKg;
    private String co2eBasis;

    /** Total route length in the requested units. JSON: {@code length}. */
    public double getLength() {
        return length;
    }

    /** Sets the length. */
    public TradeRouterProperties setLength(double length) {
        this.length = length;
        return this;
    }

    /** Unit used for the length measurement, for example "km", "naut" or "mi". JSON: {@code units}. */
    public String getUnits() {
        return units;
    }

    /** Sets the unit identifier. */
    public TradeRouterProperties setUnits(String units) {
        this.units = units;
        return this;
    }

    /** Travelling duration in hours, derived according to the request's duration policy. JSON: {@code duration_hours}. */
    public double getDurationHours() {
        return durationHours;
    }

    /** Sets the duration. */
    public TradeRouterProperties setDurationHours(double durationHours) {
        this.durationHours = durationHours;
        return this;
    }

    /**
     * How the distance was obtained, for example "road_network", "supplied", "circuity_estimate" or
     * "great_circle", or null. JSON: {@code distance_basis}.
     */
    public String getDistanceBasis() {
        return distanceBasis;
    }

    /** Sets the distance basis. */
    public TradeRouterProperties setDistanceBasis(String distanceBasis) {
        this.distanceBasis = distanceBasis;
        return this;
    }

    /**
     * Great-circle lower bound in the requested units when a road distance was supplied, routed or estimated,
     * or null. JSON: {@code straight_line_length}.
     */
    public Double getStraightLineLength() {
        return straightLineLength;
    }

    /** Sets the straight-line length. */
    public TradeRouterProperties setStraightLineLength(Double straightLineLength) {
        this.straightLineLength = straightLineLength;
        return this;
    }

    /**
     * Provider, estimator or caller provenance for the distance, or null. JSON: {@code distance_source}.
     */
    public String getDistanceSource() {
        return distanceSource;
    }

    /** Sets the distance source. */
    public TradeRouterProperties setDistanceSource(String distanceSource) {
        this.distanceSource = distanceSource;
        return this;
    }

    /**
     * Routing profile used for a road-network result, or null. JSON: {@code routing_profile}.
     */
    public String getRoutingProfile() {
        return routingProfile;
    }

    /** Sets the routing profile. */
    public TradeRouterProperties setRoutingProfile(String routingProfile) {
        this.routingProfile = routingProfile;
        return this;
    }

    /**
     * Version identifier configured for the road-network dataset, or null. JSON: {@code routing_data_version}.
     */
    public String getRoutingDataVersion() {
        return routingDataVersion;
    }

    /** Sets the routing data version. */
    public TradeRouterProperties setRoutingDataVersion(String routingDataVersion) {
        this.routingDataVersion = routingDataVersion;
        return this;
    }

    /**
     * How travelling duration was derived, such as "provider" or "assumed_speed", or null.
     * JSON: {@code duration_basis}.
     */
    public String getDurationBasis() {
        return durationBasis;
    }

    /** Sets the duration basis. */
    public TradeRouterProperties setDurationBasis(String durationBasis) {
        this.durationBasis = durationBasis;
        return this;
    }

    /**
     * How the emitted geometry was obtained, such as "road_network" or "great_circle", or null.
     * JSON: {@code geometry_basis}.
     */
    public String getGeometryBasis() {
        return geometryBasis;
    }

    /** Sets the geometry basis. */
    public TradeRouterProperties setGeometryBasis(String geometryBasis) {
        this.geometryBasis = geometryBasis;
        return this;
    }

    /**
     * Warning attached to an estimated road distance or provider fallback, or null.
     * JSON: {@code distance_warning}.
     */
    public String getDistanceWarning() {
        return distanceWarning;
    }

    /** Sets the distance warning. */
    public TradeRouterProperties setDistanceWarning(String distanceWarning) {
        this.distanceWarning = distanceWarning;
        return this;
    }

    /** Origin port details when ports are included, or null. JSON: {@code port_origin}. */
    public Port getPortOrigin() {
        return portOrigin;
    }

    /** Sets the origin port. */
    public TradeRouterProperties setPortOrigin(Port portOrigin) {
        this.portOrigin = portOrigin;
        return this;
    }

    /** Destination port details when ports are included, or null. JSON: {@code port_dest}. */
    public Port getPortDest() {
        return portDest;
    }

    /** Sets the destination port. */
    public TradeRouterProperties setPortDest(Port portDest) {
        this.portDest = portDest;
        return this;
    }

    /** Passages, straits or canals traversed along the route, or null. JSON: {@code traversed_passages}. */
    public List<String> getTraversedPassages() {
        return traversedPassages;
    }

    /** Sets the traversed passages. */
    public TradeRouterProperties setTraversedPassages(List<String> traversedPassages) {
        this.traversedPassages = traversedPassages;
        return this;
    }

    /** 1-based leg number when the feature is part of a multi-leg movement, or null. JSON: {@code leg}. */
    public Integer getLeg() {
        return leg;
    }

    /** Sets the leg number. */
    public TradeRouterProperties setLeg(Integer leg) {
        this.leg = leg;
        return this;
    }

    /** Transport mode of the leg: "sea", "road", "rail" or "air", or null. JSON: {@code mode}. */
    public String getMode() {
        return mode;
    }

    /** Sets the mode. */
    public TradeRouterProperties setMode(String mode) {
        this.mode = mode;
        return this;
    }

    /** Role of the leg: "main", "pickup" or "delivery", or null. JSON: {@code kind}. */
    public String getKind() {
        return kind;
    }

    /** Sets the kind. */
    public TradeRouterProperties setKind(String kind) {
        this.kind = kind;
        return this;
    }

    /** Code or name of the leg's start location, or null. JSON: {@code from}. */
    public String getFrom() {
        return from;
    }

    /** Sets the start label. */
    public TradeRouterProperties setFrom(String from) {
        this.from = from;
        return this;
    }

    /** Code or name of the leg's end location, or null. JSON: {@code to}. */
    public String getTo() {
        return to;
    }

    /** Sets the end label. */
    public TradeRouterProperties setTo(String to) {
        this.to = to;
        return this;
    }

    /**
     * Port time for the leg in hours: dwell at each end of a sea leg, zero for other modes. Movement legs only.
     * JSON: {@code port_hours}.
     */
    public Double getPortHours() {
        return portHours;
    }

    /** Sets the port hours. */
    public TradeRouterProperties setPortHours(Double portHours) {
        this.portHours = portHours;
        return this;
    }

    /** Sea-service operational allowance in hours. Zero for non-sea movement legs. JSON: {@code operational_allowance_hours}. */
    public Double getOperationalAllowanceHours() {
        return operationalAllowanceHours;
    }

    /** Sets the operational allowance hours. */
    public TradeRouterProperties setOperationalAllowanceHours(Double operationalAllowanceHours) {
        this.operationalAllowanceHours = operationalAllowanceHours;
        return this;
    }

    /**
     * Fraction of sea travelling time added as the operational allowance for this leg. Chosen from the corridor
     * table unless the request sets its own fraction. Sea movement legs only. JSON: {@code operational_allowance_fraction}.
     */
    public Double getOperationalAllowanceFraction() {
        return operationalAllowanceFraction;
    }

    /** Sets the operational allowance fraction. */
    public TradeRouterProperties setOperationalAllowanceFraction(Double operationalAllowanceFraction) {
        this.operationalAllowanceFraction = operationalAllowanceFraction;
        return this;
    }

    /**
     * Trade corridor the operational allowance was taken from, for example "asia-north-europe" or
     * "transpacific"; "override" when the request set its own fraction. Sea movement legs only.
     * JSON: {@code operational_allowance_corridor}.
     */
    public String getOperationalAllowanceCorridor() {
        return operationalAllowanceCorridor;
    }

    /** Sets the operational allowance corridor. */
    public TradeRouterProperties setOperationalAllowanceCorridor(String operationalAllowanceCorridor) {
        this.operationalAllowanceCorridor = operationalAllowanceCorridor;
        return this;
    }

    /** Transshipment connection time before the leg in hours. Movement legs only. JSON: {@code connection_hours}. */
    public Double getConnectionHours() {
        return connectionHours;
    }

    /** Sets the connection hours. */
    public TradeRouterProperties setConnectionHours(Double connectionHours) {
        this.connectionHours = connectionHours;
        return this;
    }

    /**
     * Modelled minimum time for the leg in hours: travel, operational allowance, port handling and connection
     * time. This is not a carrier-scheduled or guaranteed transit time. Movement legs only. JSON: {@code transit_hours}.
     */
    public Double getTransitHours() {
        return transitHours;
    }

    /** Sets the transit hours. */
    public TradeRouterProperties setTransitHours(Double transitHours) {
        this.transitHours = transitHours;
        return this;
    }

    /** Well-to-wheel CO2e intensity applied to the leg, in grams per tonne-kilometre. JSON: {@code co2e_g_per_tonne_km}. */
    public Double getCo2eGramsPerTonneKm() {
        return co2eGramsPerTonneKm;
    }

    /** Sets the CO2e intensity. */
    public TradeRouterProperties setCo2eGramsPerTonneKm(Double co2eGramsPerTonneKm) {
        this.co2eGramsPerTonneKm = co2eGramsPerTonneKm;
        return this;
    }

    /** CO2e for the leg per tonne of cargo, in kilograms. JSON: {@code co2e_kg_per_tonne}. */
    public Double getCo2eKgPerTonne() {
        return co2eKgPerTonne;
    }

    /** Sets the CO2e per tonne. */
    public TradeRouterProperties setCo2eKgPerTonne(Double co2eKgPerTonne) {
        this.co2eKgPerTonne = co2eKgPerTonne;
        return this;
    }

    /** CO2e rate per container for sea legs, in grams per TEU-kilometre, when the movement states a TEU count. JSON: {@code co2e_g_per_teu_km}. */
    public Double getCo2eGramsPerTeuKm() {
        return co2eGramsPerTeuKm;
    }

    /** Sets the CO2e rate per TEU-km. */
    public TradeRouterProperties setCo2eGramsPerTeuKm(Double co2eGramsPerTeuKm) {
        this.co2eGramsPerTeuKm = co2eGramsPerTeuKm;
        return this;
    }

    /** CO2e for the leg in kilograms, when the movement states a cargo weight or TEU count. JSON: {@code co2e_kg}. */
    public Double getCo2eKg() {
        return co2eKg;
    }

    /** Sets the CO2e in kilograms. */
    public TradeRouterProperties setCo2eKg(Double co2eKg) {
        this.co2eKg = co2eKg;
        return this;
    }

    /** How {@link #getCo2eKg()} was derived: "tonnes", "teu", or "teu_average_weight". JSON: {@code co2e_basis}. */
    public String getCo2eBasis() {
        return co2eBasis;
    }

    /** Sets the CO2e basis. */
    public TradeRouterProperties setCo2eBasis(String co2eBasis) {
        this.co2eBasis = co2eBasis;
        return this;
    }
}
