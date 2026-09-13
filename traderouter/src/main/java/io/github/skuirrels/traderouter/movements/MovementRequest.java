package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.TradeRouterOptions;
import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A multi-leg movement to be routed: an ordered list of legs plus the options and lookups needed to route
 * them. Setters return this instance for chaining.
 */
public final class MovementRequest {
    private List<MovementLeg> legs = new ArrayList<>();
    private final Map<String, Coordinate> coordinates = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private LocationResolver resolver;
    private TradeRouterOptions seaOptions;
    private EmissionFactors emissions = EmissionFactors.glecDefaults();
    private Double cargoTonnes;
    private Double cargoTeu;
    private double portDwellHours = 24.0;
    private Double seaOperationalAllowance;
    private double transshipmentConnectionHours = 48.0;
    private final Map<TransportMode, Double> speedsKmh = new EnumMap<>(TransportMode.class);

    /** Creates an empty request. */
    public MovementRequest() {
        speedsKmh.put(TransportMode.ROAD, 60.0);
        speedsKmh.put(TransportMode.RAIL, 80.0);
        speedsKmh.put(TransportMode.AIR, 800.0);
    }

    /** Creates a request for the given legs. */
    public MovementRequest(List<MovementLeg> legs) {
        this();
        setLegs(legs);
    }

    /** Creates a request for a fluent plan's legs. */
    public static MovementRequest of(MovementPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new MovementRequest(plan.legs());
    }

    /** Creates a request by parsing leg lines with {@link MovementParser}. */
    public static MovementRequest of(String legsText) {
        return new MovementRequest(MovementParser.parse(legsText));
    }

    /** Ordered legs of the movement. */
    public List<MovementLeg> getLegs() {
        return legs;
    }

    /** Sets the legs. */
    public MovementRequest setLegs(List<MovementLeg> legs) {
        this.legs = legs == null ? null : new ArrayList<>(legs);
        return this;
    }

    /**
     * Coordinates for location codes, keyed case-insensitively by code. Checked before the embedded port
     * database, so an entry here overrides a port's stored position; the port record is then not attached to
     * the leg. Use this when the embedded list disagrees with your code conventions, for example CNSHG, which
     * the list holds as Sanshan rather than the Port of Shanghai. The map is live and may be modified.
     */
    public Map<String, Coordinate> getCoordinates() {
        return coordinates;
    }

    /** Adds a coordinate for a location code. */
    public MovementRequest putCoordinate(String code, Coordinate coordinate) {
        coordinates.put(code, coordinate);
        return this;
    }

    /**
     * Optional resolver consulted last, for codes that are neither in {@link #getCoordinates()} nor in the
     * embedded port database, or null.
     */
    public LocationResolver getResolver() {
        return resolver;
    }

    /** Sets the resolver. */
    public MovementRequest setResolver(LocationResolver resolver) {
        this.resolver = resolver;
        return this;
    }

    /**
     * Options applied to every sea leg, or null for defaults. Units and vessel speed here also govern the
     * totals. {@code appendOriginDestination} is always treated as true for movement legs so that consecutive
     * legs join end to end.
     */
    public TradeRouterOptions getSeaOptions() {
        return seaOptions;
    }

    /** Sets the sea options. */
    public MovementRequest setSeaOptions(TradeRouterOptions seaOptions) {
        this.seaOptions = seaOptions;
        return this;
    }

    /** CO2e intensity per mode used for the emission figures on each leg. Defaults to the GLEC values. */
    public EmissionFactors getEmissions() {
        return emissions;
    }

    /** Sets the emission factors. */
    public MovementRequest setEmissions(EmissionFactors emissions) {
        this.emissions = emissions;
        return this;
    }

    /**
     * Cargo weight in tonnes, gross physical weight, or null. When set, each leg and the totals also report
     * absolute CO2e in kilograms; otherwise only the per-tonne figures are reported.
     */
    public Double getCargoTonnes() {
        return cargoTonnes;
    }

    /** Sets the cargo weight. */
    public MovementRequest setCargoTonnes(Double cargoTonnes) {
        this.cargoTonnes = cargoTonnes;
        return this;
    }

    /**
     * Container count in TEU (a 40-foot box is 2, a 40-foot high cube 2.25), or null. When set, sea legs use
     * the per-TEU rate instead of the per-tonne rate. Road, rail and air legs still use tonnes; if
     * {@link #getCargoTonnes()} is not given they assume the GLEC average of 10 t per TEU.
     */
    public Double getCargoTeu() {
        return cargoTeu;
    }

    /** Sets the container count. */
    public MovementRequest setCargoTeu(Double cargoTeu) {
        this.cargoTeu = cargoTeu;
        return this;
    }

    /**
     * Hours spent handling cargo at each end of every sea leg. The default is 24 hours for loading or
     * discharge. A consecutive sea leg also receives {@link #getTransshipmentConnectionHours()}.
     */
    public double getPortDwellHours() {
        return portDwellHours;
    }

    /** Sets the port dwell hours. */
    public MovementRequest setPortDwellHours(double portDwellHours) {
        this.portDwellHours = portDwellHours;
        return this;
    }

    /**
     * Fraction of sea travelling time added for normal service operations that shortest-path geometry cannot
     * represent: intermediate port calls, restricted-water slowdowns, pilotage and berth approaches. Leave
     * null, the default, and each sea leg takes the fraction for its trade corridor from
     * {@link SeaServiceAllowance}, which was fitted to observed port-to-port sailings. Set a value to apply
     * one fraction to every sea leg instead; 0 gives pure distance-divided-by-speed time.
     */
    public Double getSeaOperationalAllowance() {
        return seaOperationalAllowance;
    }

    /** Sets a fixed sea operational allowance fraction, or null for the corridor table. */
    public MovementRequest setSeaOperationalAllowance(Double seaOperationalAllowance) {
        this.seaOperationalAllowance = seaOperationalAllowance;
        return this;
    }

    /**
     * Connection time added before each sea leg that immediately follows another sea leg. The default is 48
     * hours. Set to zero when consecutive sea legs represent a through service rather than a transshipment.
     */
    public double getTransshipmentConnectionHours() {
        return transshipmentConnectionHours;
    }

    /** Sets the transshipment connection hours. */
    public MovementRequest setTransshipmentConnectionHours(double transshipmentConnectionHours) {
        this.transshipmentConnectionHours = transshipmentConnectionHours;
        return this;
    }

    /**
     * Assumed average speed in kilometres per hour for each non-sea mode, used to estimate leg duration.
     * Defaults: road 60, rail 80, air 800. Sea speed comes from {@link #getSeaOptions()}. The map is live and
     * may be modified.
     */
    public Map<TransportMode, Double> getSpeedsKmh() {
        return speedsKmh;
    }

    /** Sets the speed for a non-sea mode. */
    public MovementRequest setSpeedKmh(TransportMode mode, double speedKmh) {
        speedsKmh.put(mode, speedKmh);
        return this;
    }
}
