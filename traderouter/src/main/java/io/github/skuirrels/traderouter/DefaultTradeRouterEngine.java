package io.github.skuirrels.traderouter;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.common.RouteNormalizer;
import io.github.skuirrels.traderouter.data.EmbeddedResources;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.geojson.GeoJsonGeometry;
import io.github.skuirrels.traderouter.geojson.TradeRouterProperties;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.graph.PathResult;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.locations.UnLocodeDatabase;
import io.github.skuirrels.traderouter.movements.CorridorAllowance;
import io.github.skuirrels.traderouter.movements.LegKind;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.Location;
import io.github.skuirrels.traderouter.movements.LocationResolver;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.RoadRoutingMode;
import io.github.skuirrels.traderouter.movements.SeaServiceAllowance;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.WaypointKind;
import io.github.skuirrels.traderouter.passages.Passage;
import io.github.skuirrels.traderouter.ports.Port;
import io.github.skuirrels.traderouter.ports.PortDatabase;
import io.github.skuirrels.traderouter.ports.PortPair;
import io.github.skuirrels.traderouter.ports.PortParameters;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Default implementation of the {@link TradeRouterEngine} maritime navigation engine. Thread-safe and designed
 * for high-concurrency routing.
 */
public final class DefaultTradeRouterEngine implements TradeRouterEngine {
    private static final class DefaultHolder {
        static final DefaultTradeRouterEngine INSTANCE = new DefaultTradeRouterEngine();
    }

    private static final Set<LocationFunction> SEA_PORT_ONLY = LocationFunction.of(LocationFunction.SEA_PORT);
    private static final Set<LocationFunction> AIRPORT_ONLY = LocationFunction.of(LocationFunction.AIRPORT);
    private static final Set<LocationFunction> RAIL_TERMINAL_ONLY = LocationFunction.of(LocationFunction.RAIL_TERMINAL);
    private static final Set<LocationFunction> TERMINAL_FUNCTIONS = LocationFunction.of(
            LocationFunction.SEA_PORT, LocationFunction.RAIL_TERMINAL, LocationFunction.ROAD_TERMINAL, LocationFunction.MULTIMODAL);
    private static final Set<LocationFunction> DEPOT_FUNCTIONS = LocationFunction.of(
            LocationFunction.ROAD_TERMINAL, LocationFunction.RAIL_TERMINAL, LocationFunction.MULTIMODAL);

    private final Lazy<MaritimeGraph> lazyGraph;
    private final Lazy<PortDatabase> lazyPorts;
    private final String maritimeDistanceSource;
    private final Lazy<UnLocodeDatabase> lazyUnLocodes = new Lazy<>(EmbeddedResources::loadUnLocodes);
    private final Lazy<Map<String, String>> lazyPortCodeAliases = new Lazy<>(EmbeddedResources::loadPortCodeAliases);

    /** The default global instance with the embedded Marnet and ports datasets, created lazily on first use. */
    public static DefaultTradeRouterEngine getDefault() {
        return DefaultHolder.INSTANCE;
    }

    /** Creates an engine using the default embedded datasets, loaded lazily on first use. */
    public DefaultTradeRouterEngine() {
        this(EmbeddedResources::loadMaritimeGraph, EmbeddedResources::loadPortDatabase, "marnet");
    }

    /** Creates an engine with custom graph and port instances. The graph must be finalized with buildIndex(). */
    public DefaultTradeRouterEngine(MaritimeGraph graph, PortDatabase ports) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(ports, "ports");
        if (!graph.isReadOnly()) {
            throw new IllegalArgumentException(
                    "The custom graph must be finalized with buildIndex() before it is used by an engine.");
        }
        lazyGraph = Lazy.of(graph);
        lazyPorts = Lazy.of(ports);
        maritimeDistanceSource = "custom_graph";
    }

    /** Creates an engine with custom factories, invoked lazily on first use. */
    public DefaultTradeRouterEngine(Supplier<MaritimeGraph> graphFactory, Supplier<PortDatabase> portsFactory) {
        this(graphFactory, portsFactory, "custom_graph");
    }

    private DefaultTradeRouterEngine(
            Supplier<MaritimeGraph> graphFactory,
            Supplier<PortDatabase> portsFactory,
            String maritimeDistanceSource) {
        Objects.requireNonNull(graphFactory, "graphFactory");
        Objects.requireNonNull(portsFactory, "portsFactory");
        lazyGraph = new Lazy<>(() -> validateGraph(graphFactory.get()));
        lazyPorts = new Lazy<>(() -> {
            PortDatabase ports = portsFactory.get();
            if (ports == null) {
                throw new IllegalStateException("The port factory returned null.");
            }
            return ports;
        });
        this.maritimeDistanceSource = maritimeDistanceSource;
    }

    @Override
    public MaritimeGraph graph() {
        return lazyGraph.get();
    }

    @Override
    public PortDatabase ports() {
        return lazyPorts.get();
    }

    /** The embedded UN/LOCODE list, used to resolve movement locations that are not sea ports. */
    public UnLocodeDatabase unLocodes() {
        return lazyUnLocodes.get();
    }

    @Override
    public GeoJsonFeature calculateRoute(Coordinate origin, Coordinate destination, TradeRouterOptions options) {
        return calculateRoutes(origin, destination, options).get(0);
    }

    @Override
    public GeoJsonFeature calculateRoute(double originLon, double originLat, double destLon, double destLat, TradeRouterOptions options) {
        return calculateRoute(new Coordinate(originLon, originLat), new Coordinate(destLon, destLat), options);
    }

    @Override
    public GeoJsonFeature calculateRoute(String originPortCode, String destPortCode, TradeRouterOptions options) {
        Port originPort = getPortByCodeOrAlias(originPortCode);
        if (originPort == null) {
            throw new IllegalArgumentException("Port '" + originPortCode + "' not found in port database.");
        }
        Port destPort = getPortByCodeOrAlias(destPortCode);
        if (destPort == null) {
            throw new IllegalArgumentException("Port '" + destPortCode + "' not found in port database.");
        }

        GeoJsonFeature feature = calculateRoute(originPort.coordinate(), destPort.coordinate(), options);
        feature.properties().setPortOrigin(originPort);
        feature.properties().setPortDest(destPort);
        return feature;
    }

    /**
     * Finds a port by its code. A code the port list does not hold is looked up in the reviewed alias list, which
     * maps official UN/LOCODEs such as AGSJO to the port list's own code for the same port, here AGSJS.
     */
    private Port getPortByCodeOrAlias(String portCode) {
        if (portCode == null || portCode.isBlank()) {
            throw new IllegalArgumentException("Port code cannot be null or whitespace.");
        }
        Port port = ports().getByCode(portCode);
        if (port != null) {
            return port;
        }
        String alias = lazyPortCodeAliases.get().get(portCode.trim().toUpperCase(Locale.ROOT));
        return alias != null ? ports().getByCode(alias) : null;
    }

    @Override
    public List<GeoJsonFeature> calculateRoutes(Coordinate origin, Coordinate destination, TradeRouterOptions options) {
        Objects.requireNonNull(origin, "origin").validate();
        Objects.requireNonNull(destination, "destination").validate();

        TradeRouterOptions effective = (options == null ? new TradeRouterOptions() : options).copy();
        effective.validate();
        DistanceUnit units = effective.getUnits();
        double speedKnots = effective.getSpeedKnots();
        boolean appendOrigDest = effective.isAppendOriginDestination();
        boolean includePorts = effective.isIncludePorts();
        boolean returnPassages = effective.isReturnPassages();
        Set<String> restrictions = effective.getClosedPassages();
        String algorithm = effective.getAlgorithm();

        List<PortPair> portMatrix;
        if (includePorts) {
            List<PortPair> matrix = ports().getSelectedPortMatrix(origin, destination, effective.getPortParameters());
            portMatrix = matrix.isEmpty() ? List.of(new PortPair(null, null)) : matrix;
        } else {
            portMatrix = List.of(new PortPair(null, null));
        }

        List<GeoJsonFeature> results = new ArrayList<>(portMatrix.size());

        for (PortPair pair : portMatrix) {
            Port pFrom = pair.originPort();
            Port pTo = pair.destPort();
            Coordinate routedOrigin = pFrom != null ? pFrom.coordinate() : origin;
            Coordinate routedDest = pTo != null ? pTo.coordinate() : destination;

            PathResult shortest = graph().shortestPath(routedOrigin, routedDest, restrictions, algorithm);

            List<Coordinate> routeCoords;
            if (!shortest.found()) {
                routeCoords = new ArrayList<>();
            } else {
                // A one-node path means both endpoints snap to the same network node. Emit a straight line
                // between the routed endpoints so the LineString always has two positions and a real length.
                if (shortest.path().size() == 1) {
                    routeCoords = new ArrayList<>(2);
                    routeCoords.add(routedOrigin);
                    routeCoords.add(routedDest);
                } else {
                    routeCoords = shortest.path(); // freshly allocated per query, safe to take ownership
                }

                if (includePorts && !routeCoords.isEmpty()) {
                    if (!routeCoords.get(0).equals(routedOrigin)) {
                        routeCoords.add(0, routedOrigin);
                    }
                    if (!routeCoords.get(routeCoords.size() - 1).equals(routedDest)) {
                        routeCoords.add(routedDest);
                    }
                }

                if (appendOrigDest && !routeCoords.isEmpty()) {
                    if (!routeCoords.get(0).equals(origin)) {
                        routeCoords.add(0, origin);
                    }
                    if (!routeCoords.get(routeCoords.size() - 1).equals(destination)) {
                        routeCoords.add(destination);
                    }
                }
            }

            RouteNormalizer.ProcessedRoute processed = RouteNormalizer.processRoute(routeCoords, graph(), returnPassages);
            List<Coordinate> normalizedCoords = processed.lineString();

            double totalLength = normalizedCoords.size() > 1
                    ? Haversine.calculatePathLength(normalizedCoords, units)
                    : 0.0;
            double duration = totalLength > 0 ? Haversine.calculateDurationHours(speedKnots, totalLength, units) : 0.0;

            TradeRouterProperties properties = new TradeRouterProperties()
                    .setLength(totalLength)
                    .setUnits(units.unitString())
                    .setDurationHours(duration)
                    .setDistanceBasis("maritime_network")
                    .setDistanceSource(maritimeDistanceSource)
                    .setDurationBasis("assumed_speed")
                    .setGeometryBasis("maritime_network")
                    .setPortOrigin(includePorts ? pFrom : null)
                    .setPortDest(includePorts ? pTo : null)
                    .setTraversedPassages(returnPassages ? Passage.filterValidPassages(processed.traversedPassages()) : null);

            results.add(new GeoJsonFeature(GeoJsonGeometry.fromCoordinates(normalizedCoords), properties));
        }

        return results;
    }

    @Override
    public MovementResult calculateMovement(MovementRequest request) {
        Objects.requireNonNull(request, "request");
        validateMovement(request);
        boolean hasRoadLegWithoutOverride = false;
        List<MovementLeg> legs = request.getLegs();
        for (int i = 0; i < legs.size(); i++) {
            if (legs.get(i).mode() == TransportMode.ROAD && !request.getRoadRouteOverrides().containsKey(i + 1)) {
                hasRoadLegWithoutOverride = true;
                break;
            }
        }
        if (request.getRoadRouteProvider() != null
                && request.getRoadRoutingMode() != RoadRoutingMode.ESTIMATE_ONLY
                && hasRoadLegWithoutOverride) {
            throw new IllegalStateException(
                    "This movement is configured to call a road-route provider. Use calculateMovementAsync so provider I/O does not block the calling thread.");
        }

        // Without a provider every step completes on this thread, so the future is already done.
        CompletableFuture<MovementResult> result = calculateMovementCore(request, false);
        try {
            return result.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    @Override
    public CompletableFuture<MovementResult> calculateMovementAsync(MovementRequest request) {
        Objects.requireNonNull(request, "request");
        validateMovement(request);
        return calculateMovementCore(request, true);
    }

    /**
     * Routes the legs one after another. Each road leg may wait on the configured provider; the next leg starts
     * when it completes. Cancelling the returned future stops further legs and cancels an in-flight provider call.
     */
    private CompletableFuture<MovementResult> calculateMovementCore(MovementRequest request, boolean allowProvider) {
        // Movement legs always include their resolved endpoints so consecutive legs join end to end.
        TradeRouterOptions seaOptions = (request.getSeaOptions() == null ? new TradeRouterOptions() : request.getSeaOptions()).copy();
        seaOptions.setAppendOriginDestination(true);
        DistanceUnit units = seaOptions.getUnits();

        Map<String, ResolvedLocation> resolvedByCode = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<MovementLeg> legs = request.getLegs();
        List<LegResult> legResults = new ArrayList<>(legs.size());
        CompletableFuture<MovementResult> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();
        result.whenComplete((ignored, error) -> {
            CompletableFuture<?> pending = inFlight.get();
            if (result.isCancelled() && pending != null) {
                pending.cancel(true);
            }
        });

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < legs.size(); i++) {
            int index = i;
            chain = chain.thenCompose(ignored -> {
                if (result.isDone()) {
                    throw new CancellationException("The movement calculation was cancelled.");
                }
                return calculateLeg(index, request, allowProvider, seaOptions, units, resolvedByCode, legResults, inFlight);
            });
        }

        chain.whenComplete((ignored, error) -> {
            if (error != null) {
                result.completeExceptionally(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
            } else {
                result.complete(new MovementResult(legResults, units.unitString(), request.getCargoTonnes(), request.getCargoTeu()));
            }
        });
        return result;
    }

    private CompletableFuture<Void> calculateLeg(
            int index,
            MovementRequest request,
            boolean allowProvider,
            TradeRouterOptions seaOptions,
            DistanceUnit units,
            Map<String, ResolvedLocation> resolvedByCode,
            List<LegResult> legResults,
            AtomicReference<CompletableFuture<?>> inFlight) {
        List<MovementLeg> legs = request.getLegs();
        MovementLeg leg = legs.get(index);
        int sequence = index + 1;
        ResolvedLocation from = resolveLocation(leg.from(), request, resolvedByCode);
        ResolvedLocation to = resolveLocation(leg.to(), request, resolvedByCode);
        validateWaypoint(sequence, "from", leg.fromKind(), leg.mode(), from);
        validateWaypoint(sequence, "to", leg.toKind(), leg.mode(), to);

        if (index > 0) {
            ResolvedLocation prior = legResults.get(legResults.size() - 1).to();
            if (Haversine.distanceKm(prior.coordinate(), from.coordinate()) > 0.001) {
                throw new IllegalArgumentException(
                        "Leg " + sequence + " starts at " + from.label() + ", but leg " + (sequence - 1) + " ends at "
                                + prior.label() + ". Movement legs must form one continuous chain.");
            }
        }

        CompletableFuture<GeoJsonFeature> featureFuture;
        if (leg.mode() == TransportMode.SEA) {
            GeoJsonFeature feature = calculateSeaLeg(sequence, leg, from, to, seaOptions);
            feature.properties()
                    .setDistanceBasis("maritime_network")
                    .setDistanceSource(maritimeDistanceSource)
                    .setGeometryBasis("maritime_network")
                    .setDurationBasis("assumed_speed");
            featureFuture = CompletableFuture.completedFuture(feature);
        } else if (leg.mode() == TransportMode.ROAD) {
            featureFuture = RoadLegs.calculate(sequence, from, to, units, request, allowProvider, inFlight);
        } else {
            featureFuture = CompletableFuture.completedFuture(
                    calculateStraightLeg(from, to, leg.mode(), units, request.getSpeedsKmh()));
        }

        return featureFuture.thenAccept(feature -> {
            TradeRouterProperties properties = feature.properties();
            properties.setLeg(sequence);
            properties.setMode(leg.mode().wireString());
            properties.setKind(leg.kind().wireString());
            properties.setFrom(from.label());
            properties.setTo(to.label());
            boolean isSea = leg.mode() == TransportMode.SEA;
            boolean followsSeaLeg = isSea && sequence > 1 && legs.get(sequence - 2).mode() == TransportMode.SEA;
            properties.setPortHours(isSea ? 2.0 * request.getPortDwellHours() : 0.0);
            if (isSea) {
                CorridorAllowance allowance = request.getSeaOperationalAllowance() != null
                        ? new CorridorAllowance(SeaServiceAllowance.OVERRIDE_CORRIDOR, request.getSeaOperationalAllowance())
                        : SeaServiceAllowance.resolve(properties.getTraversedPassages(), from.coordinate(), to.coordinate());
                properties.setOperationalAllowanceFraction(allowance.fraction());
                properties.setOperationalAllowanceCorridor(allowance.corridor());
                properties.setOperationalAllowanceHours(properties.getDurationHours() * allowance.fraction());
                if (!seaOptions.isReturnPassages()) {
                    properties.setTraversedPassages(null);
                }
            } else {
                properties.setOperationalAllowanceHours(0.0);
            }
            properties.setConnectionHours(followsSeaLeg ? request.getTransshipmentConnectionHours() : 0.0);
            properties.setTransitHours(properties.getDurationHours()
                    + properties.getPortHours()
                    + properties.getOperationalAllowanceHours()
                    + properties.getConnectionHours());
            applyEmissions(feature, leg.mode(), units, request);

            legResults.add(new LegResult(sequence, leg, from, to, feature));
        });
    }

    /**
     * Stamps the leg with its GLEC-style CO2e figures: intensity in g per tonne-km, kg per tonne of cargo for
     * the leg, and absolute kg when the request states a cargo weight. Length is converted to kilometres first.
     */
    private static void applyEmissions(GeoJsonFeature feature, TransportMode mode, DistanceUnit units, MovementRequest request) {
        var factors = request.getEmissions();
        TradeRouterProperties properties = feature.properties();
        double lengthKm = properties.getLength() / (units.conversionFactorFromMeters() * 1000.0);
        double gramsPerTonneKm = factors.gramsPerTonneKm(mode, lengthKm);
        double kgPerTonne = gramsPerTonneKm * lengthKm / 1000.0;

        properties.setCo2eGramsPerTonneKm(gramsPerTonneKm);
        properties.setCo2eKgPerTonne(kgPerTonne);

        if (mode == TransportMode.SEA && request.getCargoTeu() != null) {
            // Sea legs are charged per container when a TEU count is known: a light box still moves a whole slot.
            properties.setCo2eGramsPerTeuKm(factors.getSeaGramsPerTeuKm());
            properties.setCo2eKg(factors.getSeaGramsPerTeuKm() * request.getCargoTeu() * lengthKm / 1000.0);
            properties.setCo2eBasis("teu");
            return;
        }

        // Road and rail legs of a containerised movement are charged per container too: a light box still needs a
        // whole truck or wagon slot, and the per-tonne default factors assume an average load (GLEC Framework,
        // road load factor guidance). The stated weight is used only when it exceeds the average for the TEU count.
        // Air is not containerised in TEU terms and keeps the stated weight.
        Double containerTonnes = request.getCargoTeu() != null && (mode == TransportMode.ROAD || mode == TransportMode.RAIL)
                ? request.getCargoTeu() * factors.getAverageTonnesPerTeu()
                : null;

        if (request.getCargoTonnes() != null && !(containerTonnes != null && containerTonnes > request.getCargoTonnes())) {
            properties.setCo2eKg(kgPerTonne * request.getCargoTonnes());
            properties.setCo2eBasis("tonnes");
        } else if (request.getCargoTeu() != null) {
            properties.setCo2eKg(kgPerTonne * request.getCargoTeu() * factors.getAverageTonnesPerTeu());
            properties.setCo2eBasis("teu_average_weight");
        }
    }

    private GeoJsonFeature calculateSeaLeg(int sequence, MovementLeg leg, ResolvedLocation from, ResolvedLocation to, TradeRouterOptions options) {
        // Passages are always collected for a movement leg: the corridor allowance is chosen from them.
        // calculateMovement drops them again when the caller did not ask for returnPassages.
        TradeRouterOptions routingOptions = options.isReturnPassages() ? options : options.copy();
        routingOptions.setReturnPassages(true);
        GeoJsonFeature feature = calculateRoute(from.coordinate(), to.coordinate(), routingOptions);

        if (feature.geometry() == null || feature.geometry().positions().size() < 2) {
            throw new IllegalStateException(
                    "Leg " + sequence + " (" + leg + ") has no sea route between " + from.label() + " and " + to.label()
                            + " under the current passage restrictions.");
        }

        if (feature.properties().getPortOrigin() == null) {
            feature.properties().setPortOrigin(from.port());
        }
        if (feature.properties().getPortDest() == null) {
            feature.properties().setPortDest(to.port());
        }
        return feature;
    }

    private static GeoJsonFeature calculateStraightLeg(
            ResolvedLocation from,
            ResolvedLocation to,
            TransportMode mode,
            DistanceUnit units,
            Map<TransportMode, Double> speedsKmh) {
        Double speedKmh = speedsKmh.get(mode);
        if (speedKmh == null || speedKmh <= 0) {
            throw new IllegalArgumentException(
                    "No positive speed configured for mode " + mode + ". Set MovementRequest.getSpeedsKmh() for " + mode + ".");
        }

        List<Coordinate> coords = RouteNormalizer.normalizeRoute(List.of(from.coordinate(), to.coordinate()));
        double length = Haversine.calculatePathLength(coords, units);

        // Reuse the sea-leg duration helper by expressing the configured non-sea speed in knots.
        double speedKnots = speedKmh / DistanceUnit.KM.speedCoefficient();

        TradeRouterProperties properties = new TradeRouterProperties()
                .setLength(length)
                .setUnits(units.unitString())
                .setDurationHours(Haversine.calculateDurationHours(speedKnots, length, units))
                .setDistanceBasis("great_circle")
                .setDistanceSource("built_in")
                .setDurationBasis("assumed_speed")
                .setGeometryBasis("great_circle")
                .setPortOrigin(from.port())
                .setPortDest(to.port());
        return new GeoJsonFeature(GeoJsonGeometry.fromCoordinates(coords), properties);
    }

    private ResolvedLocation resolveLocation(
            Location location,
            MovementRequest request,
            Map<String, ResolvedLocation> resolvedByCode) {
        boolean cacheable = location.code() != null && location.coordinate() == null;
        if (cacheable) {
            ResolvedLocation cached = resolvedByCode.get(location.code());
            if (cached != null) {
                return cached;
            }
        }

        ResolvedLocation resolved = resolveUncached(location, request);
        resolved.coordinate().validate();

        if (cacheable) {
            resolvedByCode.put(location.code(), resolved);
        }

        return resolved;
    }

    /**
     * Resolution order: an explicit coordinate on the location; {@link MovementRequest#getCoordinates()}; the
     * embedded port list when UN/LOCODE agrees it is the same place; the UN/LOCODE list when it carries
     * coordinates; the port list otherwise; then {@link MovementRequest#getResolver()}.
     */
    private ResolvedLocation resolveUncached(Location location, MovementRequest request) {
        if (location.coordinate() != null) {
            Port knownPort = location.code() != null ? ports().getByCode(location.code(), location.coordinate()) : null;
            UnLocode knownLocation = location.code() != null ? unLocodes().getByCode(location.code()) : null;
            Set<LocationFunction> functions = functionsOf(knownLocation, knownPort);
            String name = location.name() != null ? location.name()
                    : knownLocation != null ? knownLocation.name()
                    : knownPort != null ? knownPort.name() : null;
            return new ResolvedLocation(location.code(), name, location.coordinate(), null, "coordinates", functions);
        }

        String code = location.code();

        // A caller-supplied coordinate wins outright and no dataset record is attached, because the
        // stored position may differ from the override.
        Coordinate overridden = request.getCoordinates().get(code);
        if (overridden != null) {
            UnLocode knownLocation = unLocodes().getByCode(code);
            Port knownPort = ports().getByCode(code, overridden);
            Set<LocationFunction> functions = functionsOf(knownLocation, knownPort);
            String name = knownLocation != null ? knownLocation.name() : knownPort != null ? knownPort.name() : null;
            return new ResolvedLocation(code, name, overridden, null, "coordinates", functions);
        }

        return resolveCode(code, request.getResolver());
    }

    private static Set<LocationFunction> functionsOf(UnLocode knownLocation, Port knownPort) {
        if (knownLocation != null) {
            return knownLocation.functions();
        }
        return knownPort == null ? null : SEA_PORT_ONLY;
    }

    @Override
    public ResolvedLocation locate(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Location code cannot be null or whitespace.");
        }
        return resolveCode(code.trim().toUpperCase(Locale.ROOT), null);
    }

    private ResolvedLocation resolveCode(String code, LocationResolver resolver) {
        UnLocode unLocode = unLocodes().getByCode(code);
        Port port = unLocode != null && unLocode.coordinate() != null
                ? ports().getByCode(code, unLocode.coordinate())
                : ports().getByCode(code);

        // The port list has positions tuned to the lane network, so it wins when UN/LOCODE agrees on the name.
        // When the names disagree (the port list's CNSHG is Sanshan, UN/LOCODE's is Shanghai Pt) UN/LOCODE wins.
        if (port != null && (unLocode == null || namesAgree(port.name(), unLocode.name()))) {
            return new ResolvedLocation(code, port.name(), port.coordinate(), port, "ports",
                    unLocode != null ? unLocode.functions() : SEA_PORT_ONLY);
        }

        if (unLocode != null && unLocode.coordinate() != null) {
            return new ResolvedLocation(code, unLocode.name(), unLocode.coordinate(), null, "unlocode", unLocode.functions());
        }

        if (port != null) {
            return new ResolvedLocation(code, port.name(), port.coordinate(), port, "ports",
                    unLocode != null ? unLocode.functions() : SEA_PORT_ONLY);
        }

        if (resolver != null) {
            Optional<LocationResolver.Resolution> fromResolver = resolver.tryResolve(code);
            if (fromResolver.isPresent()) {
                LocationResolver.Resolution resolution = fromResolver.get();
                return new ResolvedLocation(code, resolution.name(), resolution.coordinate(), null, "resolver");
            }
        }

        String known = unLocode != null
                ? "UN/LOCODE knows '" + code + "' as " + unLocode.name() + " (" + describeFunctions(unLocode.functions())
                        + ") but publishes no coordinates for it."
                : "Location '" + code + "' is in neither the embedded port list nor the UN/LOCODE list.";
        throw new IllegalArgumentException(known + " Add its coordinate to MovementRequest.getCoordinates() or supply a LocationResolver.");
    }

    private static boolean namesAgree(String portName, String unLocodeName) {
        String a = normaliseName(portName);
        String b = normaliseName(unLocodeName);
        if (a.length() < 4 || b.length() < 4) {
            return a.equals(b);
        }
        return a.equals(b) || a.startsWith(b) || b.startsWith(a);
    }

    private static String normaliseName(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static String describeFunctions(Set<LocationFunction> functions) {
        if (functions == null || functions.isEmpty()) {
            return "no recorded function";
        }
        List<String> parts = new ArrayList<>(4);
        if (functions.contains(LocationFunction.SEA_PORT)) {
            parts.add("sea port");
        }
        if (functions.contains(LocationFunction.AIRPORT)) {
            parts.add("airport");
        }
        if (functions.contains(LocationFunction.RAIL_TERMINAL)) {
            parts.add("rail terminal");
        }
        if (functions.contains(LocationFunction.ROAD_TERMINAL)) {
            parts.add("road terminal");
        }
        if (functions.contains(LocationFunction.MULTIMODAL)) {
            parts.add("multimodal");
        }
        if (parts.isEmpty()) {
            List<String> identifiers = new ArrayList<>();
            for (LocationFunction function : functions) {
                identifiers.add(function.identifier());
            }
            parts.add(String.join(", ", identifiers));
        }
        return String.join(", ", parts);
    }

    private static MaritimeGraph validateGraph(MaritimeGraph graph) {
        if (graph == null) {
            throw new IllegalStateException("The graph factory returned null.");
        }
        if (!graph.isReadOnly()) {
            throw new IllegalStateException("The graph factory must return a graph finalized with buildIndex().");
        }
        return graph;
    }

    private static void validateMovement(MovementRequest request) {
        if (request.getLegs() == null || request.getLegs().isEmpty()) {
            throw new IllegalArgumentException("A movement needs at least one leg.");
        }
        if (request.getEmissions() == null) {
            throw new IllegalArgumentException("Movement emissions cannot be null.");
        }
        if (request.getRoadDistanceEstimator() == null) {
            throw new IllegalArgumentException("Movement road-distance estimator cannot be null.");
        }
        if (request.getRoadRoutingMode() == null) {
            throw new IllegalArgumentException("Movement road-routing mode is unknown.");
        }
        if (request.getRoadDurationMode() == null) {
            throw new IllegalArgumentException("Movement road-duration mode is unknown.");
        }
        request.getEmissions().validate();
        validatePositiveOptional(request.getCargoTonnes(), "cargoTonnes");
        validatePositiveOptional(request.getCargoTeu(), "cargoTeu");
        if (!Double.isFinite(request.getPortDwellHours()) || request.getPortDwellHours() < 0) {
            throw new IllegalArgumentException(
                    "Port dwell must be finite and non-negative. Received: " + request.getPortDwellHours());
        }
        Double allowance = request.getSeaOperationalAllowance();
        if (allowance != null && (!Double.isFinite(allowance) || allowance < 0)) {
            throw new IllegalArgumentException(
                    "Sea operational allowance must be finite and non-negative. Received: " + allowance);
        }
        if (!Double.isFinite(request.getTransshipmentConnectionHours()) || request.getTransshipmentConnectionHours() < 0) {
            throw new IllegalArgumentException(
                    "Transshipment connection time must be finite and non-negative. Received: "
                            + request.getTransshipmentConnectionHours());
        }
        if (request.getSpeedsKmh().containsKey(TransportMode.SEA)) {
            throw new IllegalArgumentException(
                    "Sea speed is taken from SeaOptions.getSpeedKnots(); remove the SEA entry from getSpeedsKmh().");
        }

        for (Map.Entry<TransportMode, Double> entry : request.getSpeedsKmh().entrySet()) {
            TransportMode mode = entry.getKey();
            Double speed = entry.getValue();
            if (mode == null || mode == TransportMode.SEA) {
                throw new IllegalArgumentException("SpeedsKmh contains unsupported mode '" + mode + "'.");
            }
            if (speed == null || !Double.isFinite(speed) || speed <= 0) {
                throw new IllegalArgumentException("Speed for " + mode + " must be finite and positive. Received: " + speed);
            }
        }

        (request.getSeaOptions() == null ? new TradeRouterOptions() : request.getSeaOptions()).validate();
        List<MovementLeg> legs = request.getLegs();
        for (int i = 0; i < legs.size(); i++) {
            MovementLeg leg = legs.get(i);
            if (leg == null) {
                throw new IllegalArgumentException("Movement leg " + (i + 1) + " is null.");
            }
            if (legs.size() > 1 && leg.kind() == LegKind.PICKUP && i != 0) {
                throw new IllegalArgumentException("Pickup leg " + (i + 1) + " must be the first leg.");
            }
            if (legs.size() > 1 && leg.kind() == LegKind.DELIVERY && i != legs.size() - 1) {
                throw new IllegalArgumentException("Delivery leg " + (i + 1) + " must be the last leg.");
            }
        }

        for (Map.Entry<String, Coordinate> entry : request.getCoordinates().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Movement coordinate keys cannot be blank.");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Movement coordinate for '" + entry.getKey() + "' cannot be null.");
            }
            entry.getValue().validate();
        }

        RoadLegs.validateOverrides(request);
    }

    private static void validatePositiveOptional(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value <= 0)) {
            throw new IllegalArgumentException(
                    "Cargo amount must be finite and positive when supplied. " + name + " was " + value + ".");
        }
    }

    private static void validateWaypoint(
            int sequence,
            String endpoint,
            WaypointKind waypointKind,
            TransportMode mode,
            ResolvedLocation location) {
        if (location.functions() == null) {
            return;
        }

        Set<LocationFunction> functions = location.functions();
        Set<LocationFunction> requiredByKind = switch (waypointKind) {
            case UNSPECIFIED, PLACE -> LocationFunction.NONE;
            case PORT -> SEA_PORT_ONLY;
            case AIRPORT -> AIRPORT_ONLY;
            case STATION -> RAIL_TERMINAL_ONLY;
            case TERMINAL -> TERMINAL_FUNCTIONS;
            case DEPOT -> DEPOT_FUNCTIONS;
        };

        if (!requiredByKind.isEmpty() && Collections.disjoint(functions, requiredByKind)) {
            throw new IllegalArgumentException(
                    "Leg " + sequence + " " + endpoint + " location " + location.label() + " is declared as "
                            + waypointKind.displayName() + ", but UN/LOCODE records " + describeFunctions(functions) + ".");
        }

        Set<LocationFunction> requiredByMode = switch (mode) {
            case SEA -> SEA_PORT_ONLY;
            case AIR -> AIRPORT_ONLY;
            case RAIL -> RAIL_TERMINAL_ONLY;
            case ROAD -> LocationFunction.NONE;
        };
        if (!requiredByMode.isEmpty() && Collections.disjoint(functions, requiredByMode)) {
            throw new IllegalArgumentException(
                    "Leg " + sequence + " uses " + mode.displayName() + ", but its " + endpoint + " location " + location.label()
                            + " is recorded as " + describeFunctions(functions) + " rather than a compatible "
                            + mode.wireString() + " terminal.");
        }
    }
}
