package io.github.skuirrels.traderouter.caching;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.json.JsonParser;
import io.github.skuirrels.traderouter.json.JsonWriter;
import io.github.skuirrels.traderouter.movements.RoadRouteResult;
import io.github.skuirrels.traderouter.movements.RoadRouteStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Stores road-route results in any {@link DistributedCache}, such as Redis, a SQL table or an in-process cache. Each
 * result is one JSON entry with an absolute expiry, so the store removes it when its time to live has passed.
 *
 * <p>Keys and entries are those TradeRouter.Net's {@code DistributedRoadRouteCache} writes, so Java and .NET services
 * using the same partition and key prefix share entries.
 */
public final class DistributedRoadRouteCache implements RoadRouteCache {
    /** Prefix added to every key unless another is given. */
    public static final String DEFAULT_KEY_PREFIX = "traderouter:road-route:";

    private static final int PAYLOAD_VERSION = 1;

    private final DistributedCache cache;
    private final String keyPrefix;

    /** Creates a store over a distributed cache with {@link #DEFAULT_KEY_PREFIX}. */
    public DistributedRoadRouteCache(DistributedCache cache) {
        this(cache, DEFAULT_KEY_PREFIX);
    }

    /**
     * Creates a store over a distributed cache.
     *
     * @param cache     the distributed cache
     * @param keyPrefix prefix added to every key, so road routes do not collide with other entries in a shared cache;
     *                  it may be empty
     */
    public DistributedRoadRouteCache(DistributedCache cache, String keyPrefix) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The future completes exceptionally with {@link IllegalStateException} when the stored entry is not a
     * road-route result. An entry written by a later payload version is treated as absent and overwritten.
     */
    @Override
    public CompletableFuture<RoadRouteResult> getAsync(RoadRouteCacheKey key) {
        Objects.requireNonNull(key, "key");
        CompletableFuture<byte[]> read = Objects.requireNonNull(
                cache.getAsync(keyPrefix + key.value()), "The distributed cache returned a null future.");
        CompletableFuture<RoadRouteResult> result = read.thenApply(payload -> payload == null ? null : read(payload, key));
        // A derived future does not cancel its source, so pass a cancellation on to the store's read.
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                read.cancel(true);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<Void> setAsync(RoadRouteCacheKey key, RoadRouteResult result, Duration timeToLive) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(result, "result");
        CachingRoadRouteProvider.validateTimeToLive(timeToLive, "timeToLive");

        byte[] payload = write(result).getBytes(StandardCharsets.UTF_8);
        return Objects.requireNonNull(
                cache.setAsync(keyPrefix + key.value(), payload, timeToLive), "The distributed cache returned a null future.");
    }

    // Property names, their order and the omission of nulls follow the .NET CachedRoadRoute payload. Geometry is
    // flattened to longitude, latitude pairs.
    static String write(RoadRouteResult result) {
        JsonWriter json = new JsonWriter(false).beginObject()
                .name("v").value(PAYLOAD_VERSION)
                .name("status").value(result.status().displayName());
        if (result.distanceKm() != null) {
            json.name("distanceKm").value(result.distanceKm());
        }
        if (result.durationHours() != null) {
            json.name("durationHours").value(result.durationHours());
        }
        if (result.geometry() != null) {
            json.name("geometry").beginArray();
            for (Coordinate point : result.geometry()) {
                json.value(point.longitude()).value(point.latitude());
            }
            json.endArray();
        }
        json.name("source").value(result.source());
        optional(json, "profile", result.profile());
        optional(json, "dataVersion", result.dataVersion());
        optional(json, "message", result.message());
        return json.endObject().toString();
    }

    static RoadRouteResult read(byte[] payload, RoadRouteCacheKey key) {
        Map<String, Object> entry;
        int version;
        try {
            Object parsed = JsonParser.parse(new String(payload, StandardCharsets.UTF_8));
            if (parsed == null) {
                throw new IllegalStateException("The cached road route for '" + key.value() + "' has no recognised payload version.");
            }
            entry = JsonParser.asObject(parsed);
            version = entry.get("v") == null ? 0 : JsonParser.asInt(entry.get("v"));
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new IllegalStateException("The cached road route for '" + key.value() + "' is not valid JSON.", ex);
        }

        if (version > PAYLOAD_VERSION) {
            return null;
        }
        if (version != PAYLOAD_VERSION) {
            throw new IllegalStateException("The cached road route for '" + key.value() + "' has no recognised payload version.");
        }

        try {
            RoadRouteStatus status = parseStatus(JsonParser.asString(entry.get("status")));
            String source = JsonParser.asString(entry.get("source"));
            List<Coordinate> geometry = readGeometry(entry.get("geometry"));
            if (status == null || source == null || source.isEmpty() || (entry.get("geometry") != null && geometry == null)) {
                throw new IllegalStateException("The cached road route for '" + key.value() + "' is incomplete.");
            }
            return new RoadRouteResult(
                    status,
                    optionalDouble(entry.get("distanceKm")),
                    optionalDouble(entry.get("durationHours")),
                    geometry,
                    source,
                    JsonParser.asString(entry.get("profile")),
                    JsonParser.asString(entry.get("dataVersion")),
                    JsonParser.asString(entry.get("message")));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("The cached road route for '" + key.value() + "' is not valid JSON.", ex);
        }
    }

    // .NET reads enum names without regard to case.
    private static RoadRouteStatus parseStatus(String name) {
        if (name == null) {
            return null;
        }
        for (RoadRouteStatus status : RoadRouteStatus.values()) {
            if (status.displayName().equalsIgnoreCase(name)) {
                return status;
            }
        }
        return null;
    }

    // Returns null for an absent geometry and for one with an odd number of values; the caller tells them apart.
    private static List<Coordinate> readGeometry(Object value) {
        if (value == null) {
            return null;
        }
        List<Object> flat = JsonParser.asArray(value);
        if (flat.size() % 2 != 0) {
            return null;
        }
        List<Coordinate> geometry = new ArrayList<>(flat.size() / 2);
        for (int i = 0; i < flat.size(); i += 2) {
            geometry.add(new Coordinate(JsonParser.asDouble(flat.get(i)), JsonParser.asDouble(flat.get(i + 1))));
        }
        return geometry;
    }

    private static Double optionalDouble(Object value) {
        return value == null ? null : JsonParser.asDouble(value);
    }

    private static void optional(JsonWriter json, String name, String value) {
        if (value != null) {
            json.name(name).value(value);
        }
    }
}
