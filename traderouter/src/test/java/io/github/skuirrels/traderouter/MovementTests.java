package io.github.skuirrels.traderouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.DistanceUnit;
import io.github.skuirrels.traderouter.geojson.GeoJsonFeature;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.movements.EmissionFactors;
import io.github.skuirrels.traderouter.movements.LegKind;
import io.github.skuirrels.traderouter.movements.LegResult;
import io.github.skuirrels.traderouter.movements.Location;
import io.github.skuirrels.traderouter.movements.LocationResolver;
import io.github.skuirrels.traderouter.movements.MovementFormatException;
import io.github.skuirrels.traderouter.movements.MovementLeg;
import io.github.skuirrels.traderouter.movements.MovementParser;
import io.github.skuirrels.traderouter.movements.MovementRequest;
import io.github.skuirrels.traderouter.movements.MovementResult;
import io.github.skuirrels.traderouter.movements.ResolvedLocation;
import io.github.skuirrels.traderouter.movements.TransportMode;
import io.github.skuirrels.traderouter.passages.Passage;
import io.github.skuirrels.traderouter.ports.Port;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MovementTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SHANGHAI_MOVEMENT = """
            Pickup GBLGW to Port GBFXT Road
            Port GBFXT to Port CNSHG Sea
            Delivery from port CNSHG to place CNSHZ Road
            """;

    // AUMRS is Melrose, an inland South Australian town 800 km from Melbourne, so its delivery leg is by road.
    private static final String MELBOURNE_MOVEMENT = """
            Pickup GBLGW to Port GBFXT Road
            Port GBFXT to Port SGSIN Sea
            Port SGSIN to Port AUMEL Sea
            Delivery from port AUMEL to place AUMRS Road
            """;

    // Real coordinates for codes UNECE publishes without any, used only to exercise caller overrides.
    private static final Map<String, Coordinate> EXTRA_PLACES = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    static {
        EXTRA_PLACES.put("GBLON", new Coordinate(-0.1276, 51.5072));   // London
        EXTRA_PLACES.put("AUDND", new Coordinate(145.2100, -37.9900)); // Dandenong
    }

    private static DefaultTradeRouterEngine engine() {
        return DefaultTradeRouterEngine.getDefault();
    }

    @Test
    void parserParsesPickupMainAndDeliveryLines() {
        List<MovementLeg> legs = MovementParser.parse(SHANGHAI_MOVEMENT);

        assertThat(legs).hasSize(3);

        assertThat(legs.get(0).kind()).isEqualTo(LegKind.PICKUP);
        assertThat(legs.get(0).mode()).isEqualTo(TransportMode.ROAD);
        assertThat(legs.get(0).from().code()).isEqualTo("GBLGW");
        assertThat(legs.get(0).to().code()).isEqualTo("GBFXT");

        assertThat(legs.get(1).kind()).isEqualTo(LegKind.MAIN);
        assertThat(legs.get(1).mode()).isEqualTo(TransportMode.SEA);
        assertThat(legs.get(1).from().code()).isEqualTo("GBFXT");
        assertThat(legs.get(1).to().code()).isEqualTo("CNSHG");

        assertThat(legs.get(2).kind()).isEqualTo(LegKind.DELIVERY);
        assertThat(legs.get(2).mode()).isEqualTo(TransportMode.ROAD);
        assertThat(legs.get(2).from().code()).isEqualTo("CNSHG");
        assertThat(legs.get(2).to().code()).isEqualTo("CNSHZ");
    }

    @Test
    void parserRejectsMalformedLineWithLineNumber() {
        String text = "Port GBFXT to Port SGSIN Sea\nthis is not a leg";

        assertThatThrownBy(() -> MovementParser.parse(text))
                .isInstanceOf(MovementFormatException.class)
                .hasMessageStartingWith("Line 2");
    }

    @Test
    void movementThreeLegsRoadIsStraightAndSeaIsRouted() {
        MovementResult result = TradeRoutes.calculateMovement(SHANGHAI_MOVEMENT);

        assertThat(result.legs()).hasSize(3);
        assertThat(result.units()).isEqualTo("km");

        LegResult pickup = result.legs().get(0);
        assertThat(pickup.leg().mode()).isEqualTo(TransportMode.ROAD);
        assertThat(pickup.feature().geometry().positions()).hasSize(2);
        assertThat(pickup.length()).isBetween(120.0, 160.0);
        assertThat(pickup.durationHours()).isCloseTo(pickup.length() / 60.0, within(1e-6));
        assertThat(pickup.to().port()).isNotNull();
        assertThat(pickup.to().port().name()).isEqualTo("Felixstowe");

        LegResult main = result.legs().get(1);
        assertThat(main.leg().mode()).isEqualTo(TransportMode.SEA);
        assertThat(main.feature().geometry().positions().size()).isGreaterThan(20);
        assertThat(main.feature().properties().getPortOrigin().portCode()).isEqualTo("GBFXT");
        assertThat(main.to().label()).isEqualTo("CNSHG");
        assertThat(main.to().source())
                .as("UN/LOCODE says CNSHG is Shanghai Pt while the port list says Sanshan, so UN/LOCODE wins")
                .isEqualTo("unlocode");
        assertThat(main.to().name()).isEqualTo("Shanghai Pt");
        assertThat(main.to().port()).isNull();
        assertThat(main.feature().properties().getPortDest()).isNull();
        assertThat(main.length()).as("Felixstowe to Shanghai via Suez").isBetween(19000.0, 20500.0);

        LegResult delivery = result.legs().get(2);
        assertThat(delivery.leg().kind()).isEqualTo(LegKind.DELIVERY);
        assertThat(delivery.length()).isGreaterThan(0.0);

        assertThat(result.totalLength()).isCloseTo(result.legs().stream().mapToDouble(LegResult::length).sum(), within(1e-6));
        assertThat(result.totalDurationHours()).isCloseTo(result.legs().stream().mapToDouble(LegResult::durationHours).sum(), within(1e-6));
        assertThat(result.lengthByMode().get(TransportMode.SEA)).isCloseTo(main.length(), within(1e-6));
        assertThat(result.lengthByMode().get(TransportMode.ROAD)).isCloseTo(pickup.length() + delivery.length(), within(1e-6));
    }

    @Test
    void movementFourLegsProducesFeatureCollectionWithLegMetadata() throws Exception {
        MovementResult result = TradeRoutes.calculateMovement(MELBOURNE_MOVEMENT);

        assertThat(result.legs()).hasSize(4);
        assertThat(result.legs().stream().filter(l -> l.leg().mode() == TransportMode.SEA).count()).isEqualTo(2);

        JsonNode root = JSON.readTree(result.toJson());

        assertThat(root.get("type").asText()).isEqualTo("FeatureCollection");
        JsonNode features = root.get("features");
        assertThat(features.size()).isEqualTo(4);

        JsonNode second = features.get(1).get("properties");
        assertThat(second.get("leg").asInt()).isEqualTo(2);
        assertThat(second.get("mode").asText()).isEqualTo("sea");
        assertThat(second.get("kind").asText()).isEqualTo("main");
        assertThat(second.get("from").asText()).isEqualTo("GBFXT");
        assertThat(second.get("to").asText()).isEqualTo("SGSIN");

        assertThat(root.get("properties").get("legs").asInt()).isEqualTo(4);
        assertThat(root.get("properties").get("total_length").asDouble()).isCloseTo(result.totalLength(), within(1e-6));
    }

    @Test
    void movementConsecutiveLegsJoinEndToEnd() {
        MovementResult result = TradeRoutes.calculateMovement(MELBOURNE_MOVEMENT);

        for (int i = 0; i < result.legs().size() - 1; i++) {
            List<double[]> endPositions = result.legs().get(i).feature().geometry().positions();
            double[] end = endPositions.get(endPositions.size() - 1);
            double[] start = result.legs().get(i + 1).feature().geometry().positions().get(0);
            assertThat(start[0]).as("leg %d must start where leg %d ends", i + 2, i + 1).isCloseTo(end[0], within(1e-9));
            assertThat(start[1]).isCloseTo(end[1], within(1e-9));
        }
    }

    @Test
    void movementShortSeaLegSnappingToOneNodeStillHasLength() {
        // Melbourne port and Altona, 10 km away, snap to the same Marnet node.
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg(
                        Location.fromCoordinate(new Coordinate(144.95, -37.84), "Melbourne water"),
                        Location.fromCoordinate(new Coordinate(144.82, -37.86), "Altona water"),
                        TransportMode.SEA)));
        MovementResult result = engine().calculateMovement(request);

        LegResult leg = result.legs().get(0);
        assertThat(leg.feature().geometry().positions().size()).isGreaterThanOrEqualTo(2);
        assertThat(leg.length()).isBetween(5.0, 60.0);
        assertThat(leg.durationHours()).isGreaterThan(0.0);
    }

    @Test
    void movementUnknownCodeWithoutCoordinateThrowsNamingTheCode() {
        assertThatThrownBy(() -> TradeRoutes.calculateMovement("Pickup XXZZZ to Port GBFXT Road"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XXZZZ")
                .hasMessageContaining("neither");
    }

    @Test
    void movementCodeKnownToUnLocodeButUncoordinatedThrowsWithItsName() {
        // AUDND is Dandenong: UNECE lists it without coordinates and it is not in the port list.
        assertThatThrownBy(() -> TradeRoutes.calculateMovement("Delivery from port AUMEL to place AUDND Road"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDND")
                .hasMessageContaining("Dandenong")
                .hasMessageContaining("no coordinates");
    }

    @Test
    void movementSupplementedCoordinatesResolveWithTheirSource() {
        UnLocode gatwick = engine().unLocodes().getByCode("GBLGW");
        assertThat(gatwick.coordinate()).as("the supplement file fills Gatwick").isNotNull();
        assertThat(gatwick.coordinateSource()).startsWith("Wikipedia");

        UnLocode melrose = engine().unLocodes().getByCode("AUMRS");
        assertThat(melrose.coordinate().latitude())
                .as("Melrose is in South Australia, not near Melbourne")
                .isCloseTo(-32.817, within(0.01));

        assertThat(engine().unLocodes().getByCode("SGSIN").coordinateSource()).isEqualTo("UNECE");
    }

    @Test
    void movementRejectsPortLabelWhenUnLocodeSaysAirport() {
        // The port list calls CNTSN Tianjin, while embedded UN/LOCODE records only an airport function.
        assertThatThrownBy(() -> TradeRoutes.calculateMovement("Port FRLEH to Port CNTSN Sea"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CNTSN")
                .hasMessageContaining("declared as Port")
                .hasMessageContaining("airport");
    }

    @Test
    void movementResolvesAirportsAndPortsFromUnLocodeWithoutCallerCoordinates() {
        // Heathrow and the Port of Shanghai both carry coordinates in UN/LOCODE; Felixstowe comes from the port list.
        MovementResult result = TradeRoutes.calculateMovement("Airport GBLHR to Port GBFXT Road\nPort GBFXT to Port CNSHG Sea");

        ResolvedLocation heathrow = result.legs().get(0).from();
        assertThat(heathrow.source()).isEqualTo("unlocode");
        assertThat(heathrow.name()).isEqualTo("Heathrow Apt/London");
        assertThat(heathrow.coordinate().longitude()).isCloseTo(-0.45, within(0.01));

        ResolvedLocation felixstowe = result.legs().get(0).to();
        assertThat(felixstowe.source()).as("the port list position is used when UN/LOCODE agrees on the name").isEqualTo("ports");
        assertThat(felixstowe.port()).isNotNull();

        ResolvedLocation shanghai = result.legs().get(1).to();
        assertThat(shanghai.source()).isEqualTo("unlocode");
        assertThat(shanghai.coordinate().latitude()).as("Shanghai Pt in UN/LOCODE is the Yangshan area").isCloseTo(30.63, within(0.01));

        assertThat(engine().unLocodes().count()).isGreaterThan(100000);
        assertThat(engine().unLocodes().countWithCoordinates()).isGreaterThan(80000);
    }

    @Test
    void movementUsesResolverForUnknownCodes() {
        // XXLON is in neither embedded list, so only the resolver can place it.
        MovementRequest request = MovementRequest.of("Pickup XXLON to Port GBFXT Road")
                .setResolver(stubResolver("XXLON", EXTRA_PLACES.get("GBLON"), "London"));

        MovementResult result = engine().calculateMovement(request);

        assertThat(result.legs().get(0).from().source()).isEqualTo("resolver");
        assertThat(result.legs().get(0).from().name()).isEqualTo("London");
        assertThat(result.legs().get(0).length()).isBetween(100.0, 140.0);
    }

    @Test
    void movementHonoursUnitsAndModeSpeeds() {
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg(
                        Location.fromCoordinate(EXTRA_PLACES.get("GBLON"), "London"),
                        Location.fromCoordinate(new Coordinate(1.3108, 51.9630), "Felixstowe"),
                        TransportMode.RAIL,
                        LegKind.PICKUP)))
                .setSeaOptions(new TradeRouterOptions().setUnits(DistanceUnit.NAUTICAL_MILES))
                .setSpeedKmh(TransportMode.RAIL, 100.0);

        MovementResult result = engine().calculateMovement(request);

        assertThat(result.units()).isEqualTo("naut");
        double expectedHours = result.legs().get(0).length() / (100.0 * 0.539956803);
        assertThat(result.legs().get(0).durationHours()).isCloseTo(expectedHours, within(1e-6));
    }

    @Test
    void parserReportsRealLineNumberWhenBlankLinesPrecedeTheBadLine() {
        String text = "Port GBFXT to Port SGSIN Sea\r\n\r\n\r\nthis is not a leg";

        assertThatThrownBy(() -> MovementParser.parse(text))
                .isInstanceOf(MovementFormatException.class)
                .hasMessageStartingWith("Line 4");
    }

    @Test
    void parserRejectsUnknownModeInsteadOfGuessing() {
        assertThatThrownBy(() -> MovementParser.parse("Port GBFXT to Port SGSIN Barge"))
                .isInstanceOf(MovementFormatException.class)
                .hasMessageContaining("Barge");
    }

    @Test
    void parserAcceptsModeSynonymsAndLowerCaseCodes() {
        List<MovementLeg> legs = MovementParser.parse("pickup gblgw to port gbfxt truck\nport gbfxt to port sgsin vessel");

        assertThat(legs.get(0).mode()).isEqualTo(TransportMode.ROAD);
        assertThat(legs.get(0).from().code()).isEqualTo("GBLGW");
        assertThat(legs.get(1).mode()).isEqualTo(TransportMode.SEA);
    }

    @Test
    void parserTryParseLineReturnsEmptyForUnrecognisedLines() {
        assertThat(MovementParser.tryParseLine("Port GBFXT to Port SGSIN Sea")).isPresent();
        assertThat(MovementParser.tryParseLine("not a leg")).isEmpty();
        assertThat(MovementParser.tryParseLine("   ")).isEmpty();
    }

    @Test
    void movementBlockedSeaLegThrowsNamingTheLeg() {
        // Singapore to Piraeus with Suez and Gibraltar closed has no route; the coordinate-only destination
        // also exercises Location.fromCoordinate.
        MovementRequest request = new MovementRequest(List.of(
                new MovementLeg(
                        Location.fromCode("SGSIN"),
                        Location.fromCoordinate(new Coordinate(23.62904, 37.94056), "Piraeus"),
                        TransportMode.SEA)))
                .setSeaOptions(new TradeRouterOptions().setRestrictions(Passage.NORTHWEST, Passage.SUEZ, Passage.GIBRALTAR));

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Leg 1")
                .hasMessageContaining("Piraeus");
    }

    @Test
    void movementInvalidSuppliedCoordinateThrowsForStraightLegs() {
        MovementRequest request = MovementRequest.of("Pickup GBLON to Port GBFXT Road")
                .putCoordinate("GBLON", new Coordinate(-0.19, 951.1));

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Latitude");
    }

    @Test
    void movementLowerCaseCoordinateKeysResolve() {
        MovementRequest request = MovementRequest.of("Pickup GBLON to Port GBFXT Road")
                .putCoordinate("gblon", EXTRA_PLACES.get("GBLON"));

        MovementResult result = engine().calculateMovement(request);

        assertThat(result.legs().get(0).from().source()).isEqualTo("coordinates");
        assertThat(result.legs().get(0).length()).isBetween(100.0, 140.0);
    }

    @Test
    void movementSeaSpeedInSpeedsKmhIsRejected() {
        MovementRequest request = MovementRequest.of("Port GBFXT to Port SGSIN Sea");
        request.getSpeedsKmh().put(TransportMode.SEA, 30.0);

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SpeedKnots");
    }

    @Test
    void movementResolverDoesNotOverrideEmbeddedPortCoordinates() {
        MovementRequest request = MovementRequest.of("Port GBFXT to Port SGSIN Sea")
                .setResolver(stubResolver("GBFXT", new Coordinate(100.0, 0.0), "Wrong place"));

        MovementResult result = engine().calculateMovement(request);

        Port felixstowe = engine().ports().getByCode("GBFXT");
        assertThat(result.legs().get(0).from().coordinate()).isEqualTo(felixstowe.coordinate());
        assertThat(result.legs().get(0).from().name()).isEqualTo("Felixstowe");
    }

    @Test
    void movementJunctionLocationIsResolvedOnce() {
        CountingResolver resolver = new CountingResolver(Map.of(
                "XXBBB", new Coordinate(-0.190278, 51.148056),
                "XXAAA", new Coordinate(1.0, 51.0)));
        MovementRequest request = MovementRequest.of(
                "Pickup XXBBB to Port GBFXT Road\nPort GBFXT to Port XXAAA Road\nPort XXAAA to Port GBFXT Road")
                .setResolver(resolver);

        engine().calculateMovement(request);

        assertThat(resolver.calls)
                .as("XXBBB and XXAAA are each resolved once; GBFXT comes from the port database")
                .isEqualTo(2);
    }

    @Test
    void singleRouteEndpointsSnappingToOneNodeReturnsTwoPointLine() {
        Coordinate melbourne = engine().ports().getByCode("AUMEL").coordinate();
        Coordinate altona = engine().unLocodes().getByCode("AUALT").coordinate();

        GeoJsonFeature route = TradeRoutes.calculate(melbourne, altona);

        assertThat(route.geometry().positions()).hasSize(2);
        assertThat(route.properties().getLength()).isBetween(5.0, 60.0);
    }

    @Test
    void coordinateToStringIsLocaleInvariant() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(new Coordinate(-0.19, 51.1).toString()).isEqualTo("[-0.190000, 51.100000]");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void movementAirLegsAreStraightLinesWithNoLanePointsOrChokePoints() {
        MovementRequest request = MovementRequest.of("""
                Pickup GBLGW to Airport GBLHR Road
                Airport GBLHR to Airport AUMEL Air
                Delivery from airport AUMEL to place AUMRS Road
                """)
                .setSeaOptions(new TradeRouterOptions().setReturnPassages(true));
        MovementResult result = engine().calculateMovement(request);

        LegResult flight = result.legs().get(1);
        assertThat(flight.leg().mode()).isEqualTo(TransportMode.AIR);
        assertThat(flight.feature().geometry().positions()).as("an air leg is one straight great-circle line").hasSize(2);
        assertThat(flight.length()).as("Heathrow to Melbourne great-circle distance").isBetween(16500.0, 17300.0);
        assertThat(flight.durationHours()).isCloseTo(flight.length() / 800.0, within(1e-6));
        assertThat(flight.feature().properties().getTraversedPassages()).isNullOrEmpty();
        assertThat(result.lengthByMode()).containsKeys(TransportMode.AIR, TransportMode.ROAD);
    }

    @Test
    void emissionsUseGlecDefaultsPerModeAndAirDistanceBand() {
        EmissionFactors f = EmissionFactors.glecDefaults();

        assertThat(f.gramsPerTonneKm(TransportMode.SEA, 15000)).isEqualTo(7.6);
        assertThat(f.gramsPerTonneKm(TransportMode.ROAD, 100)).isEqualTo(92.0);
        assertThat(f.gramsPerTonneKm(TransportMode.RAIL, 500)).isEqualTo(28.0);
        assertThat(f.gramsPerTonneKm(TransportMode.AIR, 999)).isEqualTo(1130.0);
        assertThat(f.gramsPerTonneKm(TransportMode.AIR, 1000)).isEqualTo(700.0);
        assertThat(f.gramsPerTonneKm(TransportMode.AIR, 3700)).isEqualTo(700.0);
        assertThat(f.gramsPerTonneKm(TransportMode.AIR, 3701)).isEqualTo(630.0);
    }

    @Test
    void emissionsPerLegAndTotalsFollowIntensityTimesDistance() {
        MovementResult result = TradeRoutes.calculateMovement(MELBOURNE_MOVEMENT, null, 20.0, null);

        LegResult road = result.legs().get(0);
        assertThat(road.co2eGramsPerTonneKm()).isEqualTo(92.0);
        assertThat(road.co2eKgPerTonne()).isCloseTo(92.0 * road.length() / 1000.0, within(1e-9));
        assertThat(road.co2eKg()).isCloseTo(road.co2eKgPerTonne() * 20.0, within(1e-9));

        LegResult sea = result.legs().get(1);
        assertThat(sea.co2eGramsPerTonneKm()).isEqualTo(7.6);
        assertThat(sea.co2eKgPerTonne()).isCloseTo(7.6 * sea.length() / 1000.0, within(1e-9));

        assertThat(result.cargoTonnes()).isEqualTo(20.0);
        assertThat(result.totalCo2eKgPerTonne()).isCloseTo(result.legs().stream().mapToDouble(LegResult::co2eKgPerTonne).sum(), within(1e-9));
        assertThat(result.totalCo2eKg()).isCloseTo(result.totalCo2eKgPerTonne() * 20.0, within(1e-9));
    }

    @Test
    void emissionsWithoutCargoWeightReportOnlyPerTonneFigures() throws Exception {
        MovementResult result = TradeRoutes.calculateMovement("Port GBFXT to Port SGSIN Sea");

        assertThat(result.cargoTonnes()).isNull();
        assertThat(result.totalCo2eKg()).isNull();
        assertThat(result.legs().get(0).co2eKg()).isNull();
        assertThat(result.legs().get(0).co2eKgPerTonne()).as("15,400 km at 7.6 g per tonne-km").isGreaterThan(100.0);

        JsonNode doc = JSON.readTree(result.toJson());
        JsonNode props = doc.get("features").get(0).get("properties");
        assertThat(props.get("co2e_g_per_tonne_km").asDouble()).isEqualTo(7.6);
        assertThat(props.has("co2e_kg")).isFalse();
        assertThat(doc.get("properties").get("total_co2e_kg_per_tonne").asDouble()).isGreaterThan(100.0);
    }

    @Test
    void emissionsConvertLengthToKilometresWhenUnitsDiffer() {
        MovementRequest request = MovementRequest.of("Port GBFXT to Port SGSIN Sea")
                .setSeaOptions(new TradeRouterOptions().setUnits(DistanceUnit.NAUTICAL_MILES));

        MovementResult inNaut = engine().calculateMovement(request);
        MovementResult inKm = TradeRoutes.calculateMovement("Port GBFXT to Port SGSIN Sea");

        assertThat(inNaut.legs().get(0).co2eKgPerTonne()).isCloseTo(inKm.legs().get(0).co2eKgPerTonne(), within(1e-6));
    }

    @Test
    void emissionsCustomFactorsAreHonoured() {
        MovementRequest request = MovementRequest.of("Pickup GBLGW to Port GBFXT Road")
                .setEmissions(new EmissionFactors().setRoadGramsPerTonneKm(50.0));

        MovementResult result = engine().calculateMovement(request);

        assertThat(result.legs().get(0).co2eGramsPerTonneKm()).isEqualTo(50.0);
    }

    @Test
    void emissionsSeaLegsUsePerTeuRateWhenTeuIsGiven() throws Exception {
        MovementResult result = TradeRoutes.calculateMovement(MELBOURNE_MOVEMENT, null, 12.0, 2.0);

        LegResult sea = result.legs().get(1);
        assertThat(sea.co2eBasis()).isEqualTo("teu");
        assertThat(sea.feature().properties().getCo2eGramsPerTeuKm()).isEqualTo(76.0);
        assertThat(sea.co2eKg()).isCloseTo(76.0 * 2.0 * sea.length() / 1000.0, within(1e-9));

        LegResult road = result.legs().get(0);
        assertThat(road.co2eBasis()).as("non-sea legs use the stated weight").isEqualTo("tonnes");
        assertThat(road.co2eKg()).isCloseTo(road.co2eKgPerTonne() * 12.0, within(1e-9));

        assertThat(result.cargoTeu()).isEqualTo(2.0);
        assertThat(result.totalCo2eKg()).isCloseTo(result.legs().stream().mapToDouble(l -> l.co2eKg()).sum(), within(1e-9));

        JsonNode doc = JSON.readTree(result.toJson());
        JsonNode seaProps = doc.get("features").get(1).get("properties");
        assertThat(seaProps.get("co2e_basis").asText()).isEqualTo("teu");
        assertThat(seaProps.get("co2e_g_per_teu_km").asDouble()).isEqualTo(76.0);
        assertThat(doc.get("properties").get("cargo_teu").asDouble()).isEqualTo(2.0);
    }

    @Test
    void emissionsTeuOnlyInfersAverageWeightForNonSeaLegs() {
        MovementResult result = TradeRoutes.calculateMovement(MELBOURNE_MOVEMENT, null, null, 1.0);

        LegResult road = result.legs().get(0);
        assertThat(road.co2eBasis()).isEqualTo("teu_average_weight");
        assertThat(road.co2eKg()).as("GLEC average of 10 t per TEU").isCloseTo(road.co2eKgPerTonne() * 10.0, within(1e-9));
        assertThat(result.legs().get(1).co2eBasis()).isEqualTo("teu");
        assertThat(result.cargoTonnes()).isNull();
        assertThat(result.totalCo2eKg()).isNotNull();
    }

    @Test
    void timeDefaultModelIncludesExplicitSeaAndConnectionAllowances() throws Exception {
        MovementResult result = TradeRoutes.calculateMovement(MELBOURNE_MOVEMENT);

        LegResult road = result.legs().get(0);
        assertThat(road.portHours()).isEqualTo(0.0);
        assertThat(road.transitHours()).isEqualTo(road.durationHours());

        LegResult sea = result.legs().get(1);
        assertThat(sea.portHours()).as("24 h at each end of a sea leg").isEqualTo(48.0);
        assertThat(sea.durationHours()).as("steaming at 16 knots").isCloseTo(sea.length() / (16.0 * 1.852), within(1e-6));
        assertThat(sea.feature().properties().getOperationalAllowanceCorridor())
                .as("Felixstowe to Singapore crosses Gibraltar, Suez and Malacca")
                .isEqualTo("asia-north-europe");
        assertThat(sea.operationalAllowanceHours()).isCloseTo(sea.durationHours() * 0.63, within(1e-9));
        assertThat(sea.connectionHours()).as("the first sea leg has no preceding service to connect from").isEqualTo(0.0);
        assertThat(sea.transitHours()).isCloseTo(sea.durationHours() * 1.63 + 48.0, within(1e-9));

        LegResult connectingSea = result.legs().get(2);
        assertThat(connectingSea.connectionHours()).as("consecutive sea legs are modelled as a transshipment").isEqualTo(48.0);

        assertThat(result.totalPortHours()).isEqualTo(2 * 48.0);
        assertThat(connectingSea.feature().properties().getOperationalAllowanceCorridor())
                .as("Singapore to Melbourne has no fitted corridor")
                .isEqualTo("default");
        assertThat(result.totalOperationalAllowanceHours()).isCloseTo(
                sea.durationHours() * 0.63 + connectingSea.durationHours() * 0.20, within(1e-9));
        assertThat(result.totalConnectionHours()).isEqualTo(48.0);
        assertThat(result.totalTransitHours()).isCloseTo(
                result.totalDurationHours()
                        + result.totalOperationalAllowanceHours()
                        + result.totalPortHours()
                        + result.totalConnectionHours(),
                within(1e-9));
        assertThat(result.totalTransitHours() / 24.0)
                .as("the corridor allowances put the UK–Melbourne movement inside the 43 to 63 days carriers publish")
                .isBetween(53.0, 56.0);

        JsonNode doc = JSON.readTree(result.toJson());
        JsonNode seaProperties = doc.get("features").get(1).get("properties");
        assertThat(seaProperties.get("port_hours").asDouble()).isEqualTo(48.0);
        assertThat(seaProperties.get("operational_allowance_hours").asDouble()).isCloseTo(sea.operationalAllowanceHours(), within(1e-9));
        assertThat(seaProperties.get("connection_hours").asDouble()).isEqualTo(0.0);
        JsonNode totals = doc.get("properties");
        assertThat(totals.get("total_operational_allowance_hours").asDouble()).isCloseTo(result.totalOperationalAllowanceHours(), within(1e-9));
        assertThat(totals.get("total_connection_hours").asDouble()).isEqualTo(48.0);
        assertThat(totals.get("total_transit_hours").asDouble()).isCloseTo(result.totalTransitHours(), within(1e-9));
    }

    @Test
    void timeAllAllowancesCanBeSwitchedOffForPureTravelTime() {
        MovementRequest request = MovementRequest.of("Port GBFXT to Port SGSIN Sea")
                .setPortDwellHours(0.0)
                .setSeaOperationalAllowance(0.0)
                .setTransshipmentConnectionHours(0.0);

        MovementResult result = engine().calculateMovement(request);

        assertThat(result.legs().get(0).portHours()).isEqualTo(0.0);
        assertThat(result.totalTransitHours()).isEqualTo(result.totalDurationHours());
    }

    @Test
    void timeInvalidOperationalAllowanceIsRejected() {
        MovementRequest request = MovementRequest.of("Port GBFXT to Port SGSIN Sea")
                .setSeaOperationalAllowance(Double.NaN);

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operational allowance");
    }

    @Test
    void timeInvalidConnectionTimeIsRejected() {
        MovementRequest request = MovementRequest.of("Port GBFXT to Port SGSIN Sea")
                .setTransshipmentConnectionHours(-1.0);

        assertThatThrownBy(() -> engine().calculateMovement(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection time");
    }

    @Test
    void locateResolvesCodesFromPortsThenUnLocode() {
        ResolvedLocation paris = TradeRoutes.locate("FRPAR");
        assertThat(paris.source()).as("Paris is not a sea port").isEqualTo("unlocode");
        assertThat(paris.coordinate().latitude()).isCloseTo(48.85, within(0.01));

        ResolvedLocation london = TradeRoutes.locate("gblon");
        assertThat(london.source()).isEqualTo("ports");
        assertThat(london.port().name()).isEqualTo("London");

        ResolvedLocation jebelAli = TradeRoutes.locate("AEJEA");
        assertThat(jebelAli.source()).as("UN/LOCODE has no coordinates for Jebel Ali").isEqualTo("ports");

        assertThatThrownBy(() -> TradeRoutes.locate("XXZZZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XXZZZ");
    }

    private static LocationResolver stubResolver(String code, Coordinate coordinate, String name) {
        return requested -> requested.equalsIgnoreCase(code)
                ? Optional.of(new LocationResolver.Resolution(coordinate, name))
                : Optional.empty();
    }

    private static final class CountingResolver implements LocationResolver {
        private final Map<String, Coordinate> entries;
        int calls;

        CountingResolver(Map<String, Coordinate> entries) {
            this.entries = entries;
        }

        @Override
        public Optional<Resolution> tryResolve(String code) {
            calls++;
            for (Map.Entry<String, Coordinate> entry : entries.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(code)) {
                    return Optional.of(new Resolution(entry.getValue(), null));
                }
            }
            return Optional.empty();
        }
    }
}
