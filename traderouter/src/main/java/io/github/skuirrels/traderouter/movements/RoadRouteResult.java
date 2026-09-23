package io.github.skuirrels.traderouter.movements;

import io.github.skuirrels.traderouter.common.Coordinate;

import java.util.List;
import java.util.Objects;

/**
 * A road-network provider's typed result.
 *
 * @param status        provider outcome
 * @param distanceKm    routed distance in kilometres; required for a successful result
 * @param durationHours provider-modelled travelling time in hours, or null
 * @param geometry      road geometry in traversal order, or null
 * @param source        stable provider identifier, for example "osrm"
 * @param profile       routing profile used by the provider, for example "driving", or null
 * @param dataVersion   optional caller-defined provenance label for the provider's road dataset, or null
 * @param message       human-readable reason for a non-success outcome, or null
 */
public record RoadRouteResult(
        RoadRouteStatus status,
        Double distanceKm,
        Double durationHours,
        List<Coordinate> geometry,
        String source,
        String profile,
        String dataVersion,
        String message) {

    /** Validates the required members and copies the geometry. */
    public RoadRouteResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        geometry = geometry == null ? null : List.copyOf(geometry);
    }

    /** Starts a result with the given outcome and provider identifier. */
    public static Builder builder(RoadRouteStatus status, String source) {
        return new Builder(status, source);
    }

    /** Builder for {@link RoadRouteResult}. */
    public static final class Builder {
        private final RoadRouteStatus status;
        private final String source;
        private Double distanceKm;
        private Double durationHours;
        private List<Coordinate> geometry;
        private String profile;
        private String dataVersion;
        private String message;

        private Builder(RoadRouteStatus status, String source) {
            this.status = status;
            this.source = source;
        }

        /** Sets the routed distance in kilometres. */
        public Builder distanceKm(Double distanceKm) {
            this.distanceKm = distanceKm;
            return this;
        }

        /** Sets the provider-modelled travelling time in hours. */
        public Builder durationHours(Double durationHours) {
            this.durationHours = durationHours;
            return this;
        }

        /** Sets the road geometry in traversal order. */
        public Builder geometry(List<Coordinate> geometry) {
            this.geometry = geometry;
            return this;
        }

        /** Sets the routing profile. */
        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        /** Sets the dataset provenance label. */
        public Builder dataVersion(String dataVersion) {
            this.dataVersion = dataVersion;
            return this;
        }

        /** Sets the reason for a non-success outcome. */
        public Builder message(String message) {
            this.message = message;
            return this;
        }

        /** Builds the result. */
        public RoadRouteResult build() {
            return new RoadRouteResult(status, distanceKm, durationHours, geometry, source, profile, dataVersion, message);
        }
    }
}
