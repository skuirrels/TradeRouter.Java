package io.github.skuirrels.traderouter.geojson;

import io.github.skuirrels.traderouter.json.JsonWriter;
import io.github.skuirrels.traderouter.ports.Port;

/**
 * Shared serializer so every GeoJSON type in the library writes the same shape. Null-valued optional
 * properties are omitted.
 */
final class GeoJsonSerializer {
    private GeoJsonSerializer() {
    }

    static String serialize(GeoJsonFeature feature, boolean writeIndented) {
        JsonWriter writer = new JsonWriter(writeIndented);
        writeFeature(writer, feature);
        return writer.toString();
    }

    static String serialize(GeoJsonFeatureCollection collection, boolean writeIndented) {
        JsonWriter writer = new JsonWriter(writeIndented);
        writer.beginObject();
        writer.name("type").value(collection.type());
        writer.name("features").beginArray();
        for (GeoJsonFeature feature : collection.features()) {
            writeFeature(writer, feature);
        }
        writer.endArray();
        if (collection.properties() != null) {
            writer.name("properties");
            writeMovementProperties(writer, collection.properties());
        }
        writer.endObject();
        return writer.toString();
    }

    private static void writeFeature(JsonWriter writer, GeoJsonFeature feature) {
        writer.beginObject();
        writer.name("type").value(feature.type());
        writer.name("geometry");
        if (feature.geometry() == null) {
            writer.nullValue();
        } else {
            feature.geometry().writeTo(writer);
        }
        writer.name("properties");
        writeProperties(writer, feature.properties());
        writer.endObject();
    }

    private static void writeProperties(JsonWriter writer, TradeRouterProperties p) {
        writer.beginObject();
        writer.name("length").value(p.getLength());
        writer.name("units").value(p.getUnits());
        writer.name("duration_hours").value(p.getDurationHours());
        optional(writer, "distance_basis", p.getDistanceBasis());
        optional(writer, "straight_line_length", p.getStraightLineLength());
        optional(writer, "distance_source", p.getDistanceSource());
        optional(writer, "routing_profile", p.getRoutingProfile());
        optional(writer, "routing_data_version", p.getRoutingDataVersion());
        optional(writer, "duration_basis", p.getDurationBasis());
        optional(writer, "geometry_basis", p.getGeometryBasis());
        optional(writer, "distance_warning", p.getDistanceWarning());
        if (p.getPortOrigin() != null) {
            writer.name("port_origin");
            writePort(writer, p.getPortOrigin());
        }
        if (p.getPortDest() != null) {
            writer.name("port_dest");
            writePort(writer, p.getPortDest());
        }
        if (p.getTraversedPassages() != null) {
            writer.name("traversed_passages").beginArray();
            for (String passage : p.getTraversedPassages()) {
                writer.value(passage);
            }
            writer.endArray();
        }
        if (p.getRailroads() != null) {
            writer.name("railroads").beginArray();
            for (String railroad : p.getRailroads()) {
                writer.value(railroad);
            }
            writer.endArray();
        }
        if (p.getLeg() != null) {
            writer.name("leg").value((long) p.getLeg());
        }
        optional(writer, "mode", p.getMode());
        optional(writer, "kind", p.getKind());
        optional(writer, "from", p.getFrom());
        optional(writer, "to", p.getTo());
        optional(writer, "port_hours", p.getPortHours());
        optional(writer, "operational_allowance_hours", p.getOperationalAllowanceHours());
        optional(writer, "operational_allowance_fraction", p.getOperationalAllowanceFraction());
        optional(writer, "operational_allowance_corridor", p.getOperationalAllowanceCorridor());
        optional(writer, "connection_hours", p.getConnectionHours());
        optional(writer, "transit_hours", p.getTransitHours());
        optional(writer, "co2e_g_per_tonne_km", p.getCo2eGramsPerTonneKm());
        optional(writer, "co2e_kg_per_tonne", p.getCo2eKgPerTonne());
        optional(writer, "co2e_g_per_teu_km", p.getCo2eGramsPerTeuKm());
        optional(writer, "co2e_kg", p.getCo2eKg());
        optional(writer, "co2e_basis", p.getCo2eBasis());
        optional(writer, "air_service", p.getAirService());
        writer.endObject();
    }

    private static void writeMovementProperties(JsonWriter writer, MovementProperties p) {
        writer.beginObject();
        writer.name("total_length").value(p.getTotalLength());
        writer.name("units").value(p.getUnits());
        writer.name("total_duration_hours").value(p.getTotalDurationHours());
        writer.name("total_port_hours").value(p.getTotalPortHours());
        writer.name("total_operational_allowance_hours").value(p.getTotalOperationalAllowanceHours());
        writer.name("total_connection_hours").value(p.getTotalConnectionHours());
        writer.name("total_transit_hours").value(p.getTotalTransitHours());
        writer.name("legs").value((long) p.getLegCount());
        writer.name("total_co2e_kg_per_tonne").value(p.getTotalCo2eKgPerTonne());
        optional(writer, "cargo_tonnes", p.getCargoTonnes());
        optional(writer, "cargo_teu", p.getCargoTeu());
        optional(writer, "total_co2e_kg", p.getTotalCo2eKg());
        writer.endObject();
    }

    static void writePort(JsonWriter writer, Port port) {
        writer.beginObject();
        writer.name("port").value(port.portCode());
        writer.name("name").value(port.name());
        writer.name("cty").value(port.country());
        writer.name("t").value(port.terminalFlag());
        writer.name("to_cty").beginArray();
        for (String country : port.toCountries()) {
            writer.value(country);
        }
        writer.endArray();
        writer.name("x").value(port.longitude());
        writer.name("y").value(port.latitude());
        optional(writer, "share", port.share());
        writer.endObject();
    }

    private static void optional(JsonWriter writer, String name, String value) {
        if (value != null) {
            writer.name(name).value(value);
        }
    }

    private static void optional(JsonWriter writer, String name, Double value) {
        if (value != null) {
            writer.name(name).value((double) value);
        }
    }
}
