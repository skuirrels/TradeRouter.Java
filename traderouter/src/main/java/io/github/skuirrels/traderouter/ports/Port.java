package io.github.skuirrels.traderouter.ports;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A maritime port from the world ports dataset.
 */
public final class Port {
    private final String portCode;
    private final String name;
    private final String country;
    private final double terminalFlag;
    private final List<String> toCountries;
    private final Coordinate coordinate;
    private final Double share;

    private Port(Builder builder) {
        this.portCode = builder.portCode;
        this.name = builder.name;
        this.country = builder.country;
        this.terminalFlag = builder.terminalFlag;
        this.toCountries = Collections.unmodifiableList(new ArrayList<>(builder.toCountries));
        this.coordinate = builder.coordinate;
        this.share = builder.share;
    }

    /** Starts building a port. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a port with a code, name, country and position, no terminal flag and no destination countries. */
    public static Port of(String portCode, String name, String country, Coordinate coordinate) {
        return builder().portCode(portCode).name(name).country(country).coordinate(coordinate).build();
    }

    /** UN/LOCODE or internal port code, for example "FRLEH" or "CNTSN". */
    public String portCode() {
        return portCode;
    }

    /** Human-readable name of the port, for example "Le Havre" or "Singapore". */
    public String name() {
        return name;
    }

    /** Country name or ISO code of the port. */
    public String country() {
        return country;
    }

    /** Terminal flag: 1.0 for a terminal port, 0.0 for a non-terminal. */
    public double terminalFlag() {
        return terminalFlag;
    }

    /** Whether this port is a major container or cargo terminal. */
    public boolean isTerminal() {
        return terminalFlag >= 1.0;
    }

    /** Destination country codes permitted from this port. */
    public List<String> toCountries() {
        return toCountries;
    }

    /** Geographical position. */
    public Coordinate coordinate() {
        return coordinate;
    }

    /** Longitude. */
    public double longitude() {
        return coordinate.longitude();
    }

    /** Latitude. */
    public double latitude() {
        return coordinate.latitude();
    }

    /** Relative share weight when used in preferred port calculations, or null. */
    public Double share() {
        return share;
    }

    /** Returns a copy of this port with an updated share. */
    public Port withShare(double share) {
        return toBuilder().share(share).build();
    }

    /** Returns a builder initialized with this port's values. */
    public Builder toBuilder() {
        return new Builder()
                .portCode(portCode)
                .name(name)
                .country(country)
                .terminalFlag(terminalFlag)
                .toCountries(toCountries)
                .coordinate(coordinate)
                .share(share);
    }

    @Override
    public String toString() {
        return portCode + " " + name + " " + coordinate;
    }

    /** Builder for {@link Port}. */
    public static final class Builder {
        private String portCode = "";
        private String name = "";
        private String country = "";
        private double terminalFlag;
        private List<String> toCountries = List.of();
        private Coordinate coordinate = new Coordinate(0.0, 0.0);
        private Double share;

        private Builder() {
        }

        /** Sets the port code. */
        public Builder portCode(String portCode) {
            this.portCode = Objects.requireNonNull(portCode, "portCode");
            return this;
        }

        /** Sets the display name. */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Sets the country. */
        public Builder country(String country) {
            this.country = Objects.requireNonNull(country, "country");
            return this;
        }

        /** Sets the terminal flag. */
        public Builder terminalFlag(double terminalFlag) {
            this.terminalFlag = terminalFlag;
            return this;
        }

        /** Sets the permitted destination countries. */
        public Builder toCountries(List<String> toCountries) {
            this.toCountries = Objects.requireNonNull(toCountries, "toCountries");
            return this;
        }

        /** Sets the position. */
        public Builder coordinate(Coordinate coordinate) {
            this.coordinate = Objects.requireNonNull(coordinate, "coordinate");
            return this;
        }

        /** Sets the share, or null for none. */
        public Builder share(Double share) {
            this.share = share;
            return this;
        }

        /** Builds the port. */
        public Port build() {
            return new Port(this);
        }
    }
}
