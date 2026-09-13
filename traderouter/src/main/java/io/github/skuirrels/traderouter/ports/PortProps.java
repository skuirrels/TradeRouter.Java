package io.github.skuirrels.traderouter.ports;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Specification for a preferred port with a selection share weight and optional custom properties.
 */
public final class PortProps {
    private final String portId;
    private final double share;
    private final Map<String, Object> props;

    /** Creates a preferred port with a share of 1. */
    public PortProps(String portId) {
        this(portId, 1.0, null);
    }

    /** Creates a preferred port with the given share. */
    public PortProps(String portId, double share) {
        this(portId, share, null);
    }

    /**
     * Creates a preferred port.
     *
     * @param portId identifier or UN/LOCODE of the port
     * @param share  selection share weight, a positive finite number
     * @param props  optional custom properties; "x" and "y" supply a coordinate for ports not in the database
     */
    public PortProps(String portId, double share, Map<String, Object> props) {
        if (portId == null || portId.isBlank()) {
            throw new IllegalArgumentException("portId cannot be null or whitespace.");
        }
        if (!Double.isFinite(share) || share <= 0) {
            throw new IllegalArgumentException("Share must be finite and greater than zero.");
        }

        this.portId = portId;
        this.share = share;
        this.props = props == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(props));
    }

    /** Identifier or UN/LOCODE of the port. */
    public String portId() {
        return portId;
    }

    /** Selection share weight. */
    public double share() {
        return share;
    }

    /** Optional custom properties or coordinates, or null. */
    public Map<String, Object> props() {
        return props;
    }

    /** Gets the coordinate when the props specify "x" and "y", otherwise null. */
    public Coordinate tryGetCoordinate() {
        if (props == null) {
            return null;
        }

        Object xObj = props.get("x");
        Object yObj = props.get("y");
        if (xObj == null || yObj == null) {
            return null;
        }
        Double lon = toDouble(xObj);
        Double lat = toDouble(yObj);
        if (lon == null || lat == null) {
            return null;
        }
        Coordinate coordinate = new Coordinate(lon, lat);
        coordinate.validate();
        return coordinate;
    }

    private static Double toDouble(Object obj) {
        if (obj instanceof Number number) {
            return number.doubleValue();
        }
        if (obj instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
