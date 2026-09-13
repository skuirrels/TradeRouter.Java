package io.github.skuirrels.traderouter.data;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.graph.MaritimeGraph;
import io.github.skuirrels.traderouter.json.JsonParser;
import io.github.skuirrels.traderouter.locations.LocationFunction;
import io.github.skuirrels.traderouter.locations.UnLocode;
import io.github.skuirrels.traderouter.locations.UnLocodeDatabase;
import io.github.skuirrels.traderouter.ports.Port;
import io.github.skuirrels.traderouter.ports.PortDatabase;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * Loads the UN/LOCODE list from the embedded {@code unlocode.json.gz} dataset: arrays of
     * {@code [code, name, longitude or null, latitude or null, function flags]}.
     */
    public static UnLocodeDatabase loadUnLocodes() {
        List<Object> items = JsonParser.asArray(JsonParser.parse(readGzipResource("unlocode.json.gz")));

        Map<String, Supplement> supplement = loadUnLocodeSupplement();
        List<UnLocode> entries = new ArrayList<>(items.size());
        for (Object item : items) {
            List<Object> row = JsonParser.asArray(item);
            String code = orEmpty(JsonParser.asString(row.get(0)));
            String name = orEmpty(JsonParser.asString(row.get(1)));
            Coordinate coordinate = JsonParser.isNumber(row.get(2)) && JsonParser.isNumber(row.get(3))
                    ? new Coordinate(JsonParser.asDouble(row.get(2)), JsonParser.asDouble(row.get(3)))
                    : null;
            var functions = LocationFunction.fromFlags(JsonParser.asInt(row.get(4)));
            String coordinateSource = coordinate != null ? "UNECE" : "";

            // The supplement only fills codes UNECE publishes without coordinates; it never overrides UNECE.
            if (coordinate == null) {
                Supplement extra = supplement.get(code.toUpperCase(Locale.ROOT));
                if (extra != null) {
                    coordinate = extra.coordinate();
                    coordinateSource = extra.source();
                }
            }

            entries.add(new UnLocode(code, name, coordinate, functions, coordinateSource));
        }

        return new UnLocodeDatabase(entries);
    }

    private record Supplement(Coordinate coordinate, String source) {
    }

    /**
     * Loads {@code unlocode-supplement.json}: researched coordinates for codes that UNECE publishes without any,
     * each with the source it was taken from.
     */
    private static Map<String, Supplement> loadUnLocodeSupplement() {
        Map<String, Supplement> result = new HashMap<>();
        String text;
        try (InputStream stream = EmbeddedResources.class.getResourceAsStream(RESOURCE_ROOT + "unlocode-supplement.json")) {
            if (stream == null) {
                return result;
            }
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read embedded resource 'unlocode-supplement.json'.", e);
        }

        for (Object item : JsonParser.asArray(JsonParser.parse(text))) {
            Map<String, Object> entry = JsonParser.asObject(item);
            String code = orEmpty(JsonParser.asString(entry.get("code")));
            Coordinate coordinate = new Coordinate(JsonParser.asDouble(entry.get("lon")), JsonParser.asDouble(entry.get("lat")));
            String source = entry.get("source") == null ? "supplement" : JsonParser.asString(entry.get("source"));
            result.put(code.toUpperCase(Locale.ROOT), new Supplement(coordinate, source));
        }
        return result;
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

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
