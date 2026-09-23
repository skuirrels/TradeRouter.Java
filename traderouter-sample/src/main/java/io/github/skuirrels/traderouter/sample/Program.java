package io.github.skuirrels.traderouter.sample;

import io.github.skuirrels.traderouter.DefaultTradeRouterEngine;
import io.github.skuirrels.traderouter.TradeRouterOptions;
import io.github.skuirrels.traderouter.TradeRoutes;
import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.movements.MovementPlan;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.OsrmRoadRouteProvider;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.RoadRoutingMode;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.movements.Waypoint;
import io.github.skuirrels.traderouter.passages.Passage;
import io.github.skuirrels.traderouter.ports.AreaFeature;
import io.github.skuirrels.traderouter.ports.PortParameters;
import io.github.skuirrels.traderouter.ports.PortProps;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * TradeRouter.Java sample: exercises each public entry point and prints the results.
 * Run with: {@code mvn -q -pl traderouter-sample exec:java}
 * Pass {@code --geojson} to print the full GeoJSON feature for the first route.
 */
public final class Program {
    private Program() {
    }

    private static final String OSRM_URL_VARIABLE = "TRADEROUTER_OSRM_URL";

    public static void main(String[] args) {
        boolean printGeoJson = Arrays.stream(args).anyMatch(arg -> arg.equalsIgnoreCase("--geojson"));

        System.out.println("TradeRouter.Java sample");
        System.out.println("=".repeat(60));

        // 1. Coordinates in, GeoJSON feature out. This is how to route a place by position: pass its longitude and
        //    latitude yourself, for a location the embedded data does not know or when you hold exact positions.
        //    Marseille and Cape Town are FRMRS and ZACPT in the data; this pair is kept as coordinates because it is
        //    the reference route the routing tests are measured against. Every other example names places by code.
        long started = System.nanoTime();
        Coordinate marseille = new Coordinate(5.333333, 43.333333);
        Coordinate capeTown = new Coordinate(18.366667, -33.916667);
        GeoJsonFeature route = TradeRoutes.calculate(marseille, capeTown, new TradeRouterOptions().setAppendOriginDestination(true));
        long coldStartMs = (System.nanoTime() - started) / 1_000_000;

        print("1. Marseille to Cape Town, given as coordinates",
                n1(route.properties().getLength()) + " " + route.properties().getUnits() + ", "
                        + n1(route.properties().getDurationHours()) + " h at 16 kn, "
                        + positionCount(route) + " points, cold start " + coldStartMs + " ms");

        // 2. Passage restrictions: Jebel Ali to St John's, Antigua, avoiding Suez, so the route goes round the Cape.
        GeoJsonFeature viaCape = TradeRoutes.calculate(at("AEJEA"), at("AGSJO"),
                new TradeRouterOptions().setRestrictions(Passage.SUEZ).setReturnPassages(true));
        print("2. AEJEA Jebel Ali to AGSJO St John's avoiding Suez",
                n0(viaCape.properties().getLength()) + " km via " + passageNames(viaCape.properties().getTraversedPassages()));

        // 2b. The Northwest Passage stays closed when you replace the restrictions; setAllowNorthwest opens it.
        GeoJsonFeature suezClosed = TradeRoutes.calculate(at("NLRTM"), at("JPYOK"),
                new TradeRouterOptions().setRestrictions(Passage.SUEZ).setReturnPassages(true));
        GeoJsonFeature arctic = TradeRoutes.calculate(at("NLRTM"), at("JPYOK"),
                new TradeRouterOptions().setReturnPassages(true).setAllowNorthwest(true));
        print("2b. NLRTM Rotterdam to JPYOK Yokohama, Suez closed, then with AllowNorthwest",
                n0(suezClosed.properties().getLength()) + " km via " + passageNames(suezClosed.properties().getTraversedPassages()) + "; "
                        + n0(arctic.properties().getLength()) + " km via " + passageNames(arctic.properties().getTraversedPassages()));

        // 3. Port codes (UN/LOCODE) straight into the port-to-port overload.
        GeoJsonFeature portToPort = TradeRoutes.calculate("FRLEH", "CNTSN");
        print("3. FRLEH to CNTSN by port code",
                portToPort.properties().getPortOrigin().name() + " to " + portToPort.properties().getPortDest().name()
                        + ", " + n0(portToPort.properties().getLength()) + " km");

        // 4. Inland places resolved to the nearest container terminals.
        GeoJsonFeature viaPorts = TradeRoutes.calculate(at("FRPAR"), at("JPTYO"), new TradeRouterOptions()
                .setIncludePorts(true)
                .setAppendOriginDestination(true)
                .setPortParameters(new PortParameters().setOnlyTerminals(true)));
        print("4. FRPAR Paris to JPTYO Tokyo via nearest terminals",
                viaPorts.properties().getPortOrigin().portCode() + " (" + viaPorts.properties().getPortOrigin().name() + ") to "
                        + viaPorts.properties().getPortDest().portCode() + " (" + viaPorts.properties().getPortDest().name() + "), "
                        + n0(viaPorts.properties().getLength()) + " km");

        // 5. Alternative algorithm and units.
        GeoJsonFeature transPacific = TradeRoutes.calculate(at("JPYOK"), at("USLAX"),
                new TradeRouterOptions().setUnits(DistanceUnit.NAUTICAL_MILES).setAlgorithm(TradeRouterOptions.ASTAR));
        print("5. JPYOK Yokohama to USLAX Los Angeles, A*, nautical miles",
                n0(transPacific.properties().getLength()) + " " + transPacific.properties().getUnits() + ", "
                        + transPacific.geometry().type() + " split at the antimeridian");

        // 6. Unreachable when every passage is closed: null geometry, zero length.
        GeoJsonFeature blocked = TradeRoutes.calculate(at("SGSIN"), at("GRPIR"),
                new TradeRouterOptions().setRestrictions(Passage.SUEZ, Passage.GIBRALTAR));
        print("6. SGSIN Singapore to GRPIR Piraeus with Suez and Gibraltar closed",
                positionCount(blocked) + " points, " + blocked.properties().getLength() + " km");

        // 7. Preferred ports per area: one route per port share. The polygon is Belgium's border, so it is coordinates.
        List<Coordinate> belgium = List.of(
                new Coordinate(2.539, 51.129), new Coordinate(2.658, 50.797), new Coordinate(3.123, 50.780), new Coordinate(4.180, 50.029),
                new Coordinate(4.885, 50.153), new Coordinate(4.844, 49.817), new Coordinate(5.368, 49.660), new Coordinate(5.463, 49.502),
                new Coordinate(5.839, 49.606), new Coordinate(5.738, 49.963), new Coordinate(6.413, 50.380), new Coordinate(5.740, 50.813),
                new Coordinate(5.823, 51.124), new Coordinate(4.706, 51.474), new Coordinate(3.830, 51.621), new Coordinate(3.315, 51.346),
                new Coordinate(2.539, 51.129));
        AreaFeature areaBelgium = new AreaFeature(belgium, "BE", List.of(new PortProps("BEANR", 250), new PortProps("FRLEH", 200)));
        List<GeoJsonFeature> routes = TradeRoutes.calculateRoutes(at("BEBRU"), at("JPTYO"), new TradeRouterOptions()
                .setIncludePorts(true)
                .setPortParameters(new PortParameters().setPortsInAreasFrom(List.of(areaBelgium))));
        List<String> shares = new ArrayList<>();
        for (GeoJsonFeature r : routes) {
            shares.add(r.properties().getPortOrigin().portCode() + " share "
                    + Math.round(r.properties().getPortOrigin().share() * 100) + "%: "
                    + n0(r.properties().getLength()) + " km");
        }
        print("7. BEBRU Brussels to JPTYO Tokyo with weighted preferred ports", String.join("; ", shares));

        // 8. GeoJSON output, ready for Leaflet, Mapbox or any GIS tool.
        String geoJson = route.toJson(printGeoJson);
        print("8. GeoJSON", printGeoJson
                ? System.lineSeparator() + geoJson
                : n0(geoJson.length()) + " characters, first 100: " + geoJson.substring(0, 100) + "...");

        // 9. The README diagram's worked example: Shanghai to London, following each step.
        ResolvedLocation shanghai = TradeRoutes.locate("CNSHG");
        ResolvedLocation london = TradeRoutes.locate("GBLON");
        MaritimeGraph graph = DefaultTradeRouterEngine.getDefault().graph();
        Coordinate shanghaiLane = graph.getCoordinate(graph.findNearestNode(shanghai.coordinate()));
        Coordinate londonLane = graph.getCoordinate(graph.findNearestNode(london.coordinate()));
        GeoJsonFeature lanePath = TradeRoutes.calculate(shanghai.coordinate(), london.coordinate(), new TradeRouterOptions().setReturnPassages(true));
        GeoJsonFeature finished = TradeRoutes.calculate(shanghai.coordinate(), london.coordinate(), new TradeRouterOptions().setAppendOriginDestination(true));
        String nl = System.lineSeparator();
        print("9. CNSHG Shanghai to GBLON London, step by step",
                "request        CNSHG " + shanghai.name() + " (" + shanghai.source() + ") to GBLON " + london.name() + " (" + london.source() + "), km, 16 knots" + nl
                        + "   snap           Shanghai lane point " + n1(Haversine.distance(shanghai.coordinate(), shanghaiLane)) + " km away, London lane point "
                        + n1(Haversine.distance(london.coordinate(), londonLane)) + " km away" + nl
                        + "   shortest path  " + positionCount(lanePath) + " lane points, " + n0(lanePath.properties().getLength()) + " km via "
                        + passageNames(lanePath.properties().getTraversedPassages()) + nl
                        + "   finished route " + positionCount(finished) + " points, " + n0(finished.properties().getLength()) + " km, "
                        + n1(finished.properties().getDurationHours()) + " h" + nl
                        + "   result         GeoJSON Feature, " + n0(finished.toJson().length()) + " characters");

        // 10 to 15. Multi-leg movements built with the fluent plan. Sea legs use the maritime network; road legs use a
        //    configured provider or the labelled built-in circuity estimate. Air legs use great-circle distance.
        //    Every code resolves from the embedded port list or UN/LOCODE list, so examples do not embed coordinates.
        //    Gatwick (GBLGW), Shanghai Railway Station (CNSHZ) and Melrose (AUMRS) have no coordinates in the UNECE
        //    list; the library's supplement file fills them from cited sources. AUMRS is Melrose, an inland South
        //    Australian town about 800 km from Melbourne, so its delivery leg is by road.

        printMovement(
                "10. Movement with one sea leg",
                MovementPlan
                        .from(Waypoint.place("GBLGW"))
                        .pickupTo(Waypoint.port("GBFXT"), TransportMode.ROAD)
                        .thenTo(Waypoint.port("CNSHG"), TransportMode.SEA)
                        .deliverTo(Waypoint.place("CNSHZ"), TransportMode.ROAD),
                20.0, null);

        printMovement(
                "11. Movement with several sea legs, a light 12 t load in one 40-foot container",
                MovementPlan
                        .from(Waypoint.place("GBLGW"))
                        .pickupTo(Waypoint.port("GBFXT"), TransportMode.ROAD)
                        .thenTo(Waypoint.port("SGSIN"), TransportMode.SEA)
                        .thenTo(Waypoint.port("AUMEL"), TransportMode.SEA)
                        .deliverTo(Waypoint.place("AUMRS"), TransportMode.ROAD),
                12.0, 2.0);

        MovementPlan airPlan = MovementPlan
                .from(Waypoint.place("GBLGW"))
                .pickupTo(Waypoint.airport("GBLHR"), TransportMode.ROAD)
                .thenTo(Waypoint.airport("AUMEL"), TransportMode.AIR)
                .deliverTo(Waypoint.place("AUMRS"), TransportMode.ROAD);
        MovementRequest airRequest = MovementRequest.of(airPlan)
                .setSeaOptions(new TradeRouterOptions().setReturnPassages(true))
                .setCargoTonnes(20.0)
                .setRoadRoutingMode(RoadRoutingMode.ESTIMATE_ONLY);
        printMovementResult("12. Movement with an air leg using built-in road estimates", TradeRoutes.calculateMovement(airRequest));

        MovementPlan roadPlan = MovementPlan
                .from(Waypoint.place("GBLGW"))
                .pickupTo(Waypoint.airport("GBLHR"), TransportMode.ROAD);
        MovementRequest estimatedRoadRequest = MovementRequest.of(roadPlan)
                .setCargoTonnes(20.0)
                .setRoadRoutingMode(RoadRoutingMode.ESTIMATE_ONLY);
        printMovementResult("13. Gatwick to Heathrow using the built-in road estimate", TradeRoutes.calculateMovement(estimatedRoadRequest));

        String osrmUrl = System.getenv(OSRM_URL_VARIABLE);
        if (osrmUrl == null || osrmUrl.isBlank()) {
            String skippedOsrm = "Skipped: set " + OSRM_URL_VARIABLE + "=http://127.0.0.1:5000 after starting OSRM as shown in the README.";
            print("14. Gatwick to Heathrow using an OSRM container", skippedOsrm);
            print("15. Cape Town to Guildford via Johannesburg and Heathrow using an OSRM container", skippedOsrm);
            return;
        }

        URI osrmBaseUri;
        try {
            osrmBaseUri = new URI(osrmUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(OSRM_URL_VARIABLE + " must be an absolute HTTP or HTTPS URI. Received: " + osrmUrl, e);
        }
        if (!osrmBaseUri.isAbsolute()) {
            throw new IllegalArgumentException(OSRM_URL_VARIABLE + " must be an absolute HTTP or HTTPS URI. Received: " + osrmUrl);
        }

        HttpClient osrmHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var osrmRoadRouteProvider = new OsrmRoadRouteProvider(
                osrmHttpClient,
                osrmBaseUri,
                OsrmRoadRouteProvider.DEFAULT_PROFILE,
                System.getenv("TRADEROUTER_OSRM_DATA_VERSION"),
                OsrmRoadRouteProvider.DEFAULT_MAXIMUM_SNAP_DISTANCE_METERS,
                Duration.ofSeconds(5));
        MovementRequest osrmRoadRequest = MovementRequest.of(roadPlan)
                .setCargoTonnes(20.0)
                .setRoadRouteProvider(osrmRoadRouteProvider)
                .setRoadRoutingMode(RoadRoutingMode.REQUIRE_NETWORK);
        printMovementResult("14. Gatwick to Heathrow using an OSRM container", TradeRoutes.calculateMovementAsync(osrmRoadRequest).join());

        MovementPlan capeTownToGuildfordPlan = MovementPlan
                .from(Waypoint.place("ZACPT"))
                .pickupTo(Waypoint.airport("ZAJNB"), TransportMode.ROAD)
                .thenTo(Waypoint.airport("GBLHR"), TransportMode.AIR)
                .deliverTo(Waypoint.place("GBGDD"), TransportMode.ROAD);
        MovementRequest capeTownToGuildfordRequest = MovementRequest.of(capeTownToGuildfordPlan)
                .setCargoTonnes(20.0)
                .setRoadRouteProvider(osrmRoadRouteProvider)
                .setRoadRoutingMode(RoadRoutingMode.REQUIRE_NETWORK);
        printMovementResult(
                "15. Cape Town to Guildford via Johannesburg and Heathrow using an OSRM container",
                TradeRoutes.calculateMovementAsync(capeTownToGuildfordRequest).join());
    }

    private static void printMovement(String title, MovementPlan plan, Double tonnes, Double teu) {
        // CO2e per leg uses GLEC well-to-wheel defaults per mode. With a TEU count, sea legs are charged per
        // container (76 g per TEU-km) rather than per tonne, so a light box is not under-counted.
        MovementRequest request = MovementRequest.of(plan)
                .setSeaOptions(new TradeRouterOptions().setReturnPassages(true))
                .setCargoTonnes(tonnes)
                .setCargoTeu(teu);
        printMovementResult(title, TradeRoutes.calculateMovement(request));
    }

    private static void printMovementResult(String title, MovementResult movement) {
        System.out.println();
        System.out.println(title);
        System.out.println(movement.toText());
    }

    private static String passageNames(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String tag : tags) {
            names.add(Passage.getDisplayName(tag));
        }
        return String.join(", ", names);
    }

    private static int positionCount(GeoJsonFeature feature) {
        return feature.geometry() == null ? 0 : feature.geometry().positions().size();
    }

    // Position of a UN/LOCODE from the embedded port list or UN/LOCODE list.
    private static Coordinate at(String code) {
        return TradeRoutes.locate(code).coordinate();
    }

    private static String n0(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    private static String n1(double value) {
        return String.format(Locale.ROOT, "%,.1f", value);
    }

    private static void print(String title, String detail) {
        System.out.println();
        System.out.println(title);
        System.out.println("   " + detail);
    }
}
