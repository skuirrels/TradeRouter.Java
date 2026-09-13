package io.github.skuirrels.traderouter.ports;

import io.github.skuirrels.traderouter.common.Coordinate;
import io.github.skuirrels.traderouter.common.Haversine;
import io.github.skuirrels.traderouter.spatial.KdItem;
import io.github.skuirrels.traderouter.spatial.KdNode;
import io.github.skuirrels.traderouter.spatial.KdTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Database of world maritime ports, indexed spatially via a KD-tree.
 */
public final class PortDatabase {
    private final List<Port> ports;
    private final Map<String, List<Port>> portsByCode;
    private final KdTree<Port> kdTree;

    /** Initializes a database over the given ports. */
    public PortDatabase(Iterable<Port> ports) {
        Objects.requireNonNull(ports, "ports");
        List<Port> portList = new ArrayList<>();
        Map<String, List<Port>> byCode = new HashMap<>();
        List<KdItem<Port>> treeItems = new ArrayList<>();
        for (Port port : ports) {
            Objects.requireNonNull(port, "Ports cannot contain null.");
            port.coordinate().validate();
            if (port.toCountries() == null) {
                throw new IllegalArgumentException("Port '" + port.portCode() + "' has a null destination-country list.");
            }
            if (!Double.isFinite(port.terminalFlag())) {
                throw new IllegalArgumentException("Port '" + port.portCode() + "' has a non-finite terminal flag.");
            }
            portList.add(port);
            treeItems.add(new KdItem<>(port.coordinate(), port));
            if (port.portCode() != null && !port.portCode().isEmpty()) {
                byCode.computeIfAbsent(normalizeCode(port.portCode()), key -> new ArrayList<>()).add(port);
            }
        }
        this.ports = Collections.unmodifiableList(portList);
        Map<String, List<Port>> frozen = new HashMap<>(byCode.size());
        for (Map.Entry<String, List<Port>> entry : byCode.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        this.portsByCode = frozen;
        this.kdTree = new KdTree<>(treeItems);
    }

    /** Total number of ports. */
    public int count() {
        return ports.size();
    }

    /** Every port record, in load order. */
    public List<Port> ports() {
        return ports;
    }

    /**
     * Gets a port by its UN/LOCODE or port code, or null when absent.
     *
     * @throws IllegalStateException when the code has several records
     */
    public Port getByCode(String portCode) {
        List<Port> candidates = getByCodeCandidates(portCode);
        return switch (candidates.size()) {
            case 0 -> null;
            case 1 -> candidates.get(0);
            default -> throw new IllegalStateException(
                    "Port code '" + portCode.trim().toUpperCase(Locale.ROOT) + "' is ambiguous (" + candidates.size()
                            + " records). Use getByCode(code, near) to select the geographically nearest record.");
        };
    }

    /** Gets every port record for a code. Some upstream port codes are not unique. */
    public List<Port> getByCodeCandidates(String portCode) {
        requireCode(portCode);
        List<Port> found = portsByCode.get(normalizeCode(portCode));
        return found == null ? List.of() : found;
    }

    /** Gets the record for a code that is geographically nearest to a known coordinate, or null. */
    public Port getByCode(String portCode, Coordinate near) {
        near.validate();
        List<Port> candidates = getByCodeCandidates(portCode);
        Port best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Port candidate : candidates) {
            double distance = Haversine.unitSphereDistanceSquared(near, candidate.coordinate());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** Finds the nearest port to a geographic coordinate, or null when the database is empty. */
    public Port findNearestPort(Coordinate coordinate) {
        KdNode<Port> nearest = kdTree.nearest(coordinate);
        return nearest == null ? null : nearest.value();
    }

    /** Finds the nearest port with no filters. */
    public Port queryClosestPort(Coordinate point) {
        return queryClosestPort(point, false, null, null, true);
    }

    /**
     * Queries the port database applying terminal and country filters.
     *
     * @param point         the point to search from
     * @param onlyTerminals whether only terminal ports qualify
     * @param country       country filter on the port's country or code prefix, or null
     * @param toCountry     destination country that must be permitted from the port, or null
     * @param strict        when true a filter matching no port yields null; when false it is dropped
     * @return the closest qualifying port, or null
     */
    public Port queryClosestPort(Coordinate point, boolean onlyTerminals, String country, String toCountry, boolean strict) {
        point.validate();

        // Fast path: when no filters are requested, query the spherical KD-tree directly in O(log N)
        if (!onlyTerminals && isBlank(country) && isBlank(toCountry)) {
            return findNearestPort(point);
        }

        List<Port> candidates = ports;

        if (onlyTerminals) {
            List<Port> terminalPorts = new ArrayList<>();
            for (Port port : candidates) {
                if (port.isTerminal()) {
                    terminalPorts.add(port);
                }
            }
            if (!terminalPorts.isEmpty() || strict) {
                candidates = terminalPorts;
            }
        }

        if (!isBlank(country)) {
            String normalizedCountry = normalizeCountry(country);
            List<Port> countryPorts = new ArrayList<>();
            for (Port port : candidates) {
                if (normalizeCountry(port.country()).equals(normalizedCountry)
                        || (normalizedCountry.length() == 2
                        && port.portCode().regionMatches(true, 0, normalizedCountry, 0, 2))) {
                    countryPorts.add(port);
                }
            }
            if (!countryPorts.isEmpty() || strict) {
                candidates = countryPorts;
            }
        }

        if (!isBlank(toCountry)) {
            String toCtyUpper = normalizeCountry(toCountry);
            List<Port> toCountryPorts = new ArrayList<>();
            for (Port port : candidates) {
                for (String tc : port.toCountries()) {
                    if (normalizeCountry(tc).equals(toCtyUpper)) {
                        toCountryPorts.add(port);
                        break;
                    }
                }
            }
            if (!toCountryPorts.isEmpty() || strict) {
                candidates = toCountryPorts;
            }
        }

        if (candidates.isEmpty()) {
            if (strict) {
                return null;
            }
            candidates = ports;
        }

        // Find closest among candidates using spherical chord distance.
        Port bestPort = null;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (Port port : candidates) {
            double dSq = Haversine.unitSphereDistanceSquared(point, port.coordinate());
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestPort = port;
            }
        }

        return bestPort;
    }

    /** Selects preferred ports matching area polygons, requiring the point to be inside an area. */
    public List<Port> getPreferredPorts(Coordinate point, List<AreaFeature> areaFeatures) {
        return getPreferredPorts(point, areaFeatures, null, true);
    }

    /**
     * Selects preferred ports matching area polygons.
     *
     * @param point        the point to locate
     * @param areaFeatures candidate areas
     * @param top          maximum number of ports to return, or null for all
     * @param strictArea   when false and no area contains the point, the nearest area within 2,000 km is used
     * @return the preferred ports with normalized shares, highest share first
     */
    public List<Port> getPreferredPorts(Coordinate point, List<AreaFeature> areaFeatures, Integer top, boolean strictArea) {
        if (areaFeatures == null || areaFeatures.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. Find smallest containing area feature
        AreaFeature smallestArea = null;
        double minArea = Double.POSITIVE_INFINITY;

        for (AreaFeature area : areaFeatures) {
            if (area.contains(point) && area.area() < minArea) {
                minArea = area.area();
                smallestArea = area;
            }
        }

        // 2. If not found and strictArea is false, find closest polygon within 2000 km
        if (smallestArea == null && !strictArea) {
            final double maxDistanceKm = 2000.0;
            double closestDistance = Double.POSITIVE_INFINITY;

            for (AreaFeature area : areaFeatures) {
                double dist = area.distanceToPoint(point);
                if (dist <= maxDistanceKm && dist < closestDistance) {
                    closestDistance = dist;
                    smallestArea = area;
                }
            }
        }

        if (smallestArea == null || smallestArea.preferredPorts().isEmpty()) {
            return new ArrayList<>();
        }

        double sumShares = 0.0;
        for (PortProps pref : smallestArea.preferredPorts()) {
            sumShares += pref.share();
        }
        if (!Double.isFinite(sumShares)) {
            throw new IllegalArgumentException(
                    "Preferred port shares in area '" + smallestArea.name() + "' overflow their finite range.");
        }
        double divisor = Math.max(sumShares, 1.0);

        List<Port> result = new ArrayList<>();
        for (PortProps pref : smallestArea.preferredPorts()) {
            double normalizedShare = pref.share() / divisor;

            Port resolvedPort;
            Port existing = getByCode(pref.portId(), point);
            if (existing != null) {
                resolvedPort = existing.withShare(normalizedShare);
            } else {
                Coordinate customCoord = pref.tryGetCoordinate();
                if (customCoord == null) {
                    throw new IllegalArgumentException(
                            "Preferred port '" + pref.portId() + "' in area '" + smallestArea.name()
                                    + "' is not in the port list and has no x/y in its props.");
                }
                resolvedPort = Port.builder()
                        .portCode(pref.portId())
                        .name(pref.portId())
                        .coordinate(customCoord)
                        .share(normalizedShare)
                        .build();
            }

            result.add(resolvedPort);
        }

        result.sort((a, b) -> Double.compare(shareOrZero(b), shareOrZero(a)));

        if (top != null && top > 0 && result.size() > top) {
            return new ArrayList<>(result.subList(0, top));
        }
        return result;
    }

    /** Generates the port matrix: combinations of origin port and destination port. */
    public List<PortPair> getSelectedPortMatrix(Coordinate origin, Coordinate destination, PortParameters parameters) {
        PortParameters params = parameters == null ? new PortParameters() : parameters;

        List<AreaFeature> areasFrom = params.getPortsInAreasFrom() != null ? params.getPortsInAreasFrom() : params.getPortsInAreas();
        List<AreaFeature> areasTo = params.getPortsInAreasTo() != null ? params.getPortsInAreasTo() : params.getPortsInAreas();

        List<Port> originPorts = new ArrayList<>();
        if (areasFrom != null && !areasFrom.isEmpty()) {
            originPorts = getPreferredPorts(origin, areasFrom, null, params.isStrictArea());
        }

        if (originPorts.isEmpty()) {
            String toCty = params.isCountryRestricted() ? params.getCountryPod() : null;
            Port closest = queryClosestPort(origin, params.isOnlyTerminals(), params.getCountryPol(), toCty, params.isStrict());
            if (closest != null) {
                originPorts.add(closest.withShare(1.0));
            }
        }

        List<Port> destPorts = new ArrayList<>();
        if (areasTo != null && !areasTo.isEmpty()) {
            destPorts = getPreferredPorts(destination, areasTo, null, params.isStrictArea());
        }

        if (destPorts.isEmpty()) {
            Port closest = queryClosestPort(destination, params.isOnlyTerminals(), params.getCountryPod(), null, params.isStrict());
            if (closest != null) {
                destPorts.add(closest.withShare(1.0));
            }
        }

        List<PortPair> matrix = new ArrayList<>();
        for (Port fromPort : originPorts) {
            for (Port toPort : destPorts) {
                matrix.add(new PortPair(fromPort, toPort));
            }
        }

        return matrix;
    }

    private static double shareOrZero(Port port) {
        return port.share() == null ? 0.0 : port.share();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireCode(String portCode) {
        if (portCode == null || portCode.isBlank()) {
            throw new IllegalArgumentException("Port code cannot be null or whitespace.");
        }
    }

    private static String normalizeCode(String portCode) {
        return portCode.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeCountry(String country) {
        StringBuilder sb = new StringBuilder(country.length());
        for (int i = 0; i < country.length(); i++) {
            char c = country.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }
}
