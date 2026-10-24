package io.github.skuirrels.traderouter.data;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.json.JsonParser;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.locations.UnLocodeDatabase;
import io.github.skuirrels.traderouter.ports.Port;
import io.github.skuirrels.traderouter.ports.PortDatabase;
import io.github.skuirrels.traderouter.seaareas.SeaAreaInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Loads and deserializes the embedded compressed datasets into in-memory data structures.
 */
public final class EmbeddedResources {
    private static final String RESOURCE_ROOT = "/io/github/skuirrels/traderouter/data/";

    private EmbeddedResources() {
    }

    /** Loads and builds the {@link MaritimeGraph} from the embedded {@code marnet.json.gz} dataset. */
    public static MaritimeGraph loadMaritimeGraph() {
        MaritimeGraph graph = new MaritimeGraph();

        Map<String, Object> root = JsonParser.asObject(JsonParser.parse(readGzipResource("marnet.json.gz")));
        Object nodesElement = root.get("nodes");
        if (nodesElement instanceof List<?>) {
            for (Object nodeItem : JsonParser.asArray(nodesElement)) {
                List<Object> node = JsonParser.asArray(nodeItem);
                double lon = JsonParser.asDouble(node.get(0));
                double lat = JsonParser.asDouble(node.get(1));
                graph.addNode(new Coordinate(lon, lat));
            }
        }

        Object edgesElement = root.get("edges");
        if (edgesElement instanceof List<?>) {
            for (Object edgeItem : JsonParser.asArray(edgesElement)) {
                List<Object> edge = JsonParser.asArray(edgeItem);
                int u = JsonParser.asInt(edge.get(0));
                int v = JsonParser.asInt(edge.get(1));
                double weight = JsonParser.asDouble(edge.get(2));
                String passage = edge.get(3) instanceof String s ? s : null;

                graph.addDirectedEdge(u, v, weight, passage);
            }
        }

        graph.buildIndex();
        return graph;
    }

    /**
     * Loads and builds the North American rail graph from the embedded {@code rail-narn.json.gz} dataset: the main
     * line of the BTS North American Rail Network. Each edge's label is the reporting mark of the railroad that owns
     * the track, and its weight is the published track length in kilometres.
     */
    public static MaritimeGraph loadRailGraph() {
        MaritimeGraph graph = new MaritimeGraph();

        Map<String, Object> root = JsonParser.asObject(JsonParser.parse(readGzipResource("rail-narn.json.gz")));
        List<Object> ownerItems = JsonParser.asArray(root.get("owners"));
        String[] owners = new String[ownerItems.size()];
        for (int i = 0; i < owners.length; i++) {
            owners[i] = JsonParser.asString(ownerItems.get(i));
        }
        for (Object nodeItem : JsonParser.asArray(root.get("nodes"))) {
            List<Object> node = JsonParser.asArray(nodeItem);
            graph.addNode(new Coordinate(JsonParser.asDouble(node.get(0)), JsonParser.asDouble(node.get(1))));
        }

        // Track runs both ways, so each stored edge is added in both directions.
        for (Object edgeItem : JsonParser.asArray(root.get("edges"))) {
            List<Object> edge = JsonParser.asArray(edgeItem);
            int u = JsonParser.asInt(edge.get(0));
            int v = JsonParser.asInt(edge.get(1));
            double weight = JsonParser.asDouble(edge.get(2));
            String mark = owners[JsonParser.asInt(edge.get(3))];
            String owner = mark != null && !mark.isEmpty() ? mark : null;
            graph.addDirectedEdge(u, v, weight, owner);
            graph.addDirectedEdge(v, u, weight, owner);
        }

        graph.buildIndex();
        return graph;
    }

    /**
     * Loads the UN/LOCODE list from the embedded {@code unlocode.json.gz} dataset: arrays of
     * {@code [code, name, longitude or null, latitude or null, function flags]}.
     */
    public static UnLocodeDatabase loadUnLocodes() {
        List<Object> items = JsonParser.asArray(JsonParser.parse(readGzipResource("unlocode.json.gz")));

        Map<String, Supplement> supplement = loadUnLocodeSupplement();
        Set<String> confirmedSeaPorts = loadFunctionSupplement("unlocode-seaport-supplement.json");
        Set<String> confirmedRailTerminals = loadFunctionSupplement("unlocode-rail-supplement.json");
        List<UnLocode> entries = new ArrayList<>(items.size());
        for (Object item : items) {
            List<Object> row = JsonParser.asArray(item);
            String code = orEmpty(JsonParser.asString(row.get(0)));
            String name = orEmpty(JsonParser.asString(row.get(1)));
            Coordinate coordinate = JsonParser.isNumber(row.get(2)) && JsonParser.isNumber(row.get(3))
                    ? new Coordinate(JsonParser.asDouble(row.get(2)), JsonParser.asDouble(row.get(3)))
                    : null;
            int flags = JsonParser.asInt(row.get(4));
            // Codes UNECE lists without the sea-port function but that are documented deep-sea terminals.
            if (confirmedSeaPorts.contains(code.toUpperCase(Locale.ROOT))) {
                flags |= LocationFunction.SEA_PORT.bit();
            }
            // Codes UNECE lists without the rail function but that are documented freight rail terminals.
            if (confirmedRailTerminals.contains(code.toUpperCase(Locale.ROOT))) {
                flags |= LocationFunction.RAIL_TERMINAL.bit();
            }
            var functions = LocationFunction.fromFlags(flags);
            String coordinateSource = coordinate != null ? "UNECE" : "";

            // The supplement fills codes UNECE publishes without coordinates; a reviewed row also replaces a UNECE
            // coordinate its source shows to be wrong. Generated GeoNames rows exist only for missing codes.
            Supplement extra = supplement.get(code.toUpperCase(Locale.ROOT));
            if (extra != null) {
                coordinate = extra.coordinate();
                coordinateSource = extra.source();
            }

            entries.add(new UnLocode(code, name, coordinate, functions, coordinateSource));
        }

        return new UnLocodeDatabase(entries);
    }

    private record Supplement(Coordinate coordinate, String source) {
    }

    /**
     * Loads {@code unlocode-supplement.json.gz}: researched coordinates for codes that UNECE publishes without any,
     * each with the source it was taken from.
     */
    private static Map<String, Supplement> loadUnLocodeSupplement() {
        Map<String, Supplement> result = new HashMap<>();
        for (Object item : JsonParser.asArray(JsonParser.parse(readGzipResource("unlocode-supplement.json.gz")))) {
            Map<String, Object> entry = JsonParser.asObject(item);
            String code = orEmpty(JsonParser.asString(entry.get("code")));
            Coordinate coordinate = new Coordinate(JsonParser.asDouble(entry.get("lon")), JsonParser.asDouble(entry.get("lat")));
            String source = entry.get("source") == null ? "supplement" : JsonParser.asString(entry.get("source"));
            result.put(code.toUpperCase(Locale.ROOT), new Supplement(coordinate, source));
        }
        return result;
    }

    /**
     * Loads a function supplement: {@code unlocode-seaport-supplement.json} lists codes that UNECE publishes without
     * the sea-port function but that cited sources document as sea ports; {@code unlocode-rail-supplement.json} does
     * the same for freight rail terminals. Each entry only adds its function and never removes one.
     */
    private static Set<String> loadFunctionSupplement(String fileName) {
        Set<String> result = new HashSet<>();
        String text = readTextResource(fileName);
        if (text == null) {
            return result;
        }

        for (Object item : JsonParser.asArray(JsonParser.parse(text))) {
            String code = orEmpty(JsonParser.asString(JsonParser.asObject(item).get("code")));
            if (!code.isEmpty()) {
                result.add(code.toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    /**
     * Loads {@code sea-areas.json}: the selection of Natural Earth 1:50m marine polygons reported as sea areas, with
     * each area's identifier, English name, Wikidata cross-reference and Natural Earth {@code ne_id}.
     */
    public static List<SeaAreaInfo> loadSeaAreaCatalogue() {
        String text = readTextResource("sea-areas.json");
        if (text == null) {
            throw new IllegalStateException("Embedded resource 'sea-areas.json' not found.");
        }

        List<SeaAreaInfo> result = new ArrayList<>();
        for (Object item : JsonParser.asArray(JsonParser.parse(text))) {
            Map<String, Object> entry = JsonParser.asObject(item);
            result.add(new SeaAreaInfo(
                    required(entry, "id", "A sea area has no id."),
                    required(entry, "name", "A sea area has no name."),
                    required(entry, "wikidata", "A sea area has no Wikidata item."),
                    JsonParser.asLong(entry.get("ne_id"))));
        }
        return List.copyOf(result);
    }

    /**
     * Loads {@code sea-area-polygons.json.gz}: each selected area's rings keyed by Natural Earth {@code ne_id}, every
     * ring a flat array of longitude, latitude pairs. Outer rings and holes of every part are kept alike; the
     * even-odd rule tells them apart.
     */
    public static Map<Long, List<double[]>> loadSeaAreaPolygons() {
        Map<String, Object> root = JsonParser.asObject(JsonParser.parse(readGzipResource("sea-area-polygons.json.gz")));

        Map<Long, List<double[]>> ringsByNeId = new HashMap<>();
        for (Object areaItem : JsonParser.asArray(root.get("areas"))) {
            Map<String, Object> area = JsonParser.asObject(areaItem);
            List<double[]> rings = new ArrayList<>();
            for (Object polygon : JsonParser.asArray(area.get("polygons"))) {
                for (Object ringItem : JsonParser.asArray(polygon)) {
                    List<Object> ring = JsonParser.asArray(ringItem);
                    double[] flat = new double[ring.size() * 2];
                    int i = 0;
                    for (Object positionItem : ring) {
                        List<Object> position = JsonParser.asArray(positionItem);
                        flat[i++] = JsonParser.asDouble(position.get(0));
                        flat[i++] = JsonParser.asDouble(position.get(1));
                    }
                    rings.add(flat);
                }
            }
            ringsByNeId.put(JsonParser.asLong(area.get("ne_id")), List.copyOf(rings));
        }
        return Map.copyOf(ringsByNeId);
    }

    /**
     * Loads {@code port-code-aliases.json}: official UN/LOCODE sea-port codes mapped to the differently coded record
     * that the embedded port list holds for the same port, reviewed from name and position. Keys are upper case.
     */
    public static Map<String, String> loadPortCodeAliases() {
        Map<String, String> result = new HashMap<>();
        String text = readTextResource("port-code-aliases.json");
        if (text == null) {
            return Map.of();
        }

        for (Object item : JsonParser.asArray(JsonParser.parse(text))) {
            Map<String, Object> entry = JsonParser.asObject(item);
            String code = orEmpty(JsonParser.asString(entry.get("code")));
            String port = orEmpty(JsonParser.asString(entry.get("port")));
            if (!code.isEmpty() && !port.isEmpty()) {
                result.put(code.toUpperCase(Locale.ROOT), port);
            }
        }
        return Map.copyOf(result);
    }

    /** Loads and builds the {@link PortDatabase} from the embedded {@code ports.json.gz} dataset. */
    public static PortDatabase loadPortDatabase() {
        List<Port> ports = new ArrayList<>();

        Object root = JsonParser.parse(readGzipResource("ports.json.gz"));
        if (!(root instanceof List<?>)) {
            return new PortDatabase(ports);
        }

        for (Object portItem : JsonParser.asArray(root)) {
            Map<String, Object> portElem = JsonParser.asObject(portItem);
            String portCode = orEmpty(JsonParser.asString(portElem.get("port")));
            String name = orEmpty(JsonParser.asString(portElem.get("name")));
            String cty = orEmpty(JsonParser.asString(portElem.get("cty")));
            double t = portElem.get("t") == null ? 0.0 : JsonParser.asDouble(portElem.get("t"));
            double x = portElem.get("x") == null ? 0.0 : JsonParser.asDouble(portElem.get("x"));
            double y = portElem.get("y") == null ? 0.0 : JsonParser.asDouble(portElem.get("y"));

            List<String> toCountries = new ArrayList<>();
            if (portElem.get("to_cty") instanceof List<?>) {
                for (Object item : JsonParser.asArray(portElem.get("to_cty"))) {
                    String s = JsonParser.asString(item);
                    if (s != null && !s.isEmpty()) {
                        toCountries.add(s);
                    }
                }
            }

            ports.add(Port.builder()
                    .portCode(portCode)
                    .name(name)
                    .country(cty)
                    .terminalFlag(t)
                    .toCountries(toCountries)
                    .coordinate(new Coordinate(x, y))
                    .build());
        }

        return new PortDatabase(ports);
    }

    private static String readTextResource(String name) {
        try (InputStream stream = EmbeddedResources.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read embedded resource '" + name + "'.", e);
        }
    }

    private static String readGzipResource(String name) {
        try (InputStream raw = EmbeddedResources.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (raw == null) {
                throw new IllegalStateException("Embedded resource '" + name + "' not found.");
            }
            try (InputStream gzip = new GZIPInputStream(raw, 64 * 1024)) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read embedded resource '" + name + "'.", e);
        }
    }

    private static String required(Map<String, Object> entry, String key, String message) {
        String value = JsonParser.asString(entry.get(key));
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
