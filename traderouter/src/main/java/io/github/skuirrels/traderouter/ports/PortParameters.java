package io.github.skuirrels.traderouter.ports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parameters for filtering, selection and polygon-weighting of maritime ports. Setters return this instance
 * for chaining.
 */
public final class PortParameters {
    private boolean onlyTerminals;
    private String countryPol;
    private String countryPod;
    private boolean countryRestricted;
    private boolean strict = true;
    private boolean strictArea = true;
    private List<AreaFeature> portsInAreasFrom;
    private List<AreaFeature> portsInAreasTo;
    private List<AreaFeature> portsInAreas;

    /** Whether to consider only major terminals. Default false. */
    public boolean isOnlyTerminals() {
        return onlyTerminals;
    }

    /** Sets whether to consider only major terminals. */
    public PortParameters setOnlyTerminals(boolean onlyTerminals) {
        this.onlyTerminals = onlyTerminals;
        return this;
    }

    /** Country code or name filter for the port of loading (origin), or null. */
    public String getCountryPol() {
        return countryPol;
    }

    /** Sets the port-of-loading country filter. */
    public PortParameters setCountryPol(String countryPol) {
        this.countryPol = countryPol;
        return this;
    }

    /** Country code or name filter for the port of discharge (destination), or null. */
    public String getCountryPod() {
        return countryPod;
    }

    /** Sets the port-of-discharge country filter. */
    public PortParameters setCountryPod(String countryPod) {
        this.countryPod = countryPod;
        return this;
    }

    /** Whether the destination country restriction is enforced against the origin port's allowed countries. */
    public boolean isCountryRestricted() {
        return countryRestricted;
    }

    /** Sets whether the destination country restriction is enforced. */
    public PortParameters setCountryRestricted(boolean countryRestricted) {
        this.countryRestricted = countryRestricted;
        return this;
    }

    /**
     * When true, the default, a terminal or country filter that matches no port yields no port, and the route
     * falls back to the raw coordinates. When false the filter is dropped and the nearest port of any kind is
     * used.
     */
    public boolean isStrict() {
        return strict;
    }

    /** Sets strict filtering. */
    public PortParameters setStrict(boolean strict) {
        this.strict = strict;
        return this;
    }

    /** Whether a point must be strictly inside an area polygon. Default true. */
    public boolean isStrictArea() {
        return strictArea;
    }

    /** Sets strict area matching. */
    public PortParameters setStrictArea(boolean strictArea) {
        this.strictArea = strictArea;
        return this;
    }

    /** Preferred port areas for the origin point, or null. */
    public List<AreaFeature> getPortsInAreasFrom() {
        return portsInAreasFrom;
    }

    /** Sets the preferred port areas for the origin point. */
    public PortParameters setPortsInAreasFrom(List<AreaFeature> portsInAreasFrom) {
        this.portsInAreasFrom = portsInAreasFrom;
        return this;
    }

    /** Preferred port areas for the destination point, or null. */
    public List<AreaFeature> getPortsInAreasTo() {
        return portsInAreasTo;
    }

    /** Sets the preferred port areas for the destination point. */
    public PortParameters setPortsInAreasTo(List<AreaFeature> portsInAreasTo) {
        this.portsInAreasTo = portsInAreasTo;
        return this;
    }

    /** Preferred port areas applied to both origin and destination when not specified individually, or null. */
    public List<AreaFeature> getPortsInAreas() {
        return portsInAreas;
    }

    /** Sets the preferred port areas applied to both ends. */
    public PortParameters setPortsInAreas(List<AreaFeature> portsInAreas) {
        this.portsInAreas = portsInAreas;
        return this;
    }

    /** Returns a snapshot whose area lists cannot be changed through the source parameters. */
    public PortParameters copy() {
        return new PortParameters()
                .setOnlyTerminals(onlyTerminals)
                .setCountryPol(countryPol)
                .setCountryPod(countryPod)
                .setCountryRestricted(countryRestricted)
                .setStrict(strict)
                .setStrictArea(strictArea)
                .setPortsInAreasFrom(snapshot(portsInAreasFrom))
                .setPortsInAreasTo(snapshot(portsInAreasTo))
                .setPortsInAreas(snapshot(portsInAreas));
    }

    /** Validates the parameters, rejecting null areas. */
    public void validate() {
        validateAreas(portsInAreasFrom, "portsInAreasFrom");
        validateAreas(portsInAreasTo, "portsInAreasTo");
        validateAreas(portsInAreas, "portsInAreas");
    }

    private static List<AreaFeature> snapshot(List<AreaFeature> areas) {
        return areas == null ? null : Collections.unmodifiableList(new ArrayList<>(areas));
    }

    private static void validateAreas(List<AreaFeature> areas, String name) {
        if (areas != null && areas.contains(null)) {
            throw new IllegalArgumentException(name + " cannot contain null areas.");
        }
    }
}
