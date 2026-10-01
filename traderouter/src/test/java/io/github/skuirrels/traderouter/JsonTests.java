package io.github.skuirrels.traderouter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.skuirrels.traderouter.json.JsonParser;
import io.github.skuirrels.traderouter.json.JsonWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parserReadsEveryValueKind() {
        Object parsed = JsonParser.parse("""
                {"a": [1, -2.5, 3e2, "x\\"y\\u0041", true, false, null], "b": {}, "c": []}
                """);

        Map<String, Object> root = JsonParser.asObject(parsed);
        List<Object> a = JsonParser.asArray(root.get("a"));
        assertThat(a.get(0)).isEqualTo(1L);
        assertThat(a.get(1)).isEqualTo(-2.5);
        assertThat(a.get(2)).isEqualTo(300.0);
        assertThat(a.get(3)).isEqualTo("x\"yA");
        assertThat(a.get(4)).isEqualTo(Boolean.TRUE);
        assertThat(a.get(5)).isEqualTo(Boolean.FALSE);
        assertThat(a.get(6)).isNull();
        assertThat(JsonParser.asObject(root.get("b"))).isEmpty();
        assertThat(JsonParser.asArray(root.get("c"))).isEmpty();
        assertThat(JsonParser.asInt(a.get(2))).isEqualTo(300);
    }

    @Test
    void parserRejectsMalformedInput() {
        assertThatThrownBy(() -> JsonParser.parse("{\"a\": }")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonParser.parse("[1, 2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonParser.parse("[1] x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonParser.asInt(2.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parserLimitsNestingDepthInsteadOfOverflowingTheStack() {
        int max = JsonParser.MAX_DEPTH;
        assertThat(JsonParser.parse("[".repeat(max) + "]".repeat(max))).isInstanceOf(List.class);
        assertThat(JsonParser.parse("{\"a\":".repeat(max) + "1" + "}".repeat(max))).isInstanceOf(Map.class);
        assertThat(JsonParser.parse("[[1],[2],[3]]")).isInstanceOf(List.class);

        assertThatThrownBy(() -> JsonParser.parse("[".repeat(max + 1) + "]".repeat(max + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum depth");
        assertThatThrownBy(() -> JsonParser.parse("[".repeat(200_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum depth");
    }

    @Test
    void writerProducesCompactAndIndentedJsonThatJacksonAccepts() throws Exception {
        String compact = sample(false);
        String indented = sample(true);

        assertThat(compact).isEqualTo("{\"name\":\"a \\\"b\\\"\",\"n\":1,\"d\":2.5,\"flag\":true,\"none\":null,\"list\":[[1,2],[3.25,-4]],\"empty\":{}}");
        assertThat(indented).isEqualTo("""
                {
                  "name": "a \\"b\\"",
                  "n": 1,
                  "d": 2.5,
                  "flag": true,
                  "none": null,
                  "list": [
                    [
                      1,
                      2
                    ],
                    [
                      3.25,
                      -4
                    ]
                  ],
                  "empty": {}
                }""");

        JsonNode fromCompact = JSON.readTree(compact);
        JsonNode fromIndented = JSON.readTree(indented);
        assertThat(fromIndented).isEqualTo(fromCompact);
        assertThat(JsonParser.parse(compact)).isEqualTo(JsonParser.parse(indented));
    }

    @Test
    void writerFormatsDoublesWithoutTrailingZeroForIntegralValues() {
        assertThat(JsonWriter.formatDouble(0.0)).isEqualTo("0");
        assertThat(JsonWriter.formatDouble(48.0)).isEqualTo("48");
        assertThat(JsonWriter.formatDouble(10996.763)).isEqualTo("10996.763");
        assertThat(JsonWriter.formatDouble(0.63)).isEqualTo("0.63");
        assertThat(JsonWriter.formatDouble(-0.0)).isEqualTo("-0");
        assertThatThrownBy(() -> new JsonWriter(false).value(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String sample(boolean indented) {
        JsonWriter writer = new JsonWriter(indented);
        writer.beginObject();
        writer.name("name").value("a \"b\"");
        writer.name("n").value(1L);
        writer.name("d").value(2.5);
        writer.name("flag").value(true);
        writer.name("none").nullValue();
        writer.name("list").beginArray();
        writer.position(new double[] {1, 2});
        writer.position(new double[] {3.25, -4});
        writer.endArray();
        writer.name("empty").beginObject().endObject();
        writer.endObject();
        return writer.toString();
    }
}
