# Embedded data provenance

This file records the exact inputs and transformations used for the binary resources in `traderouter/src/main/resources/io/github/skuirrels/traderouter/data`.
SHA-256 values are hashes of the files as stored, not of their decompressed JSON.

## Maritime network and ports

- Distribution: [genthalili/searoute-py 1.6.0](https://github.com/genthalili/searoute-py/tree/1.6.0), commit `dde461382a60ff75f4577f6e06c411bc48cd7efc`.
- Source `marnet_searoute.geojson`: SHA-256 `111a82d949bb949c396a69a08828781314d3afe779c07e9754c3b2834dfca415`.
- Source `segment.geojson`: SHA-256 `56052b2ddd35de483ec00a29a7f0ffc37e162b0ee0249a9a435c8a8b2d8ffe0f`.
- Source `ports.geojson`: SHA-256 `8398cb9554a19cf37f53f932f3842f58fbd94fce4c8e2704c3f5a4205ea5977e`.
- Embedded `marnet.json.gz`: SHA-256 `8d9be0fb7881b77f46d21bd066779cd4c561f8f70c80f02a5e381706ff7aa880`; 9,708 nodes and 31,950 directed edges.
- Embedded `ports.json.gz`: SHA-256 `ccf97018a8d8c9cccafc08d69388dc0b483e39520f24bde94b36d6a8f8d2dca2`; 3,962 records.

`tools/build-marnet-data.py` combines the tagged Marnet network with its tagged antimeridian segments, converts every line to directed edges in both directions, and calculates weights with the same mean-earth-radius Haversine formula used by the library. `tools/build-ports-data.py` retains every source port record, including duplicate codes. Both scripts verify their input hashes and write deterministic gzip files.

The searoute-py project credits [Eurostat SeaRoute](https://github.com/eurostat/searoute) as the origin of the maritime network. Eurostat describes that network as based on the Oak Ridge National Labs CTA global shipping lane network, enriched around European coasts with AIS-derived lines.

## UN/LOCODE

- Publisher: [United Nations Economic Commission for Europe, UN/LOCODE](https://unlocode.unece.org/).
- Embedded `unlocode.json.gz`: SHA-256 `6e39b6efc925d804af5b304a4db2f701782cc4226fe31e2b97cd3b0bccfb052a`; 106,588 codes, of which 84,516 contain publisher coordinates.
- Embedded `unlocode-supplement.json`: SHA-256 `6ce12ca501948e6f8c5e962fe901673627e2d05a9e1a666118896261a2437716`; four attributed coordinate supplements, applied only when the UN/LOCODE row has no coordinate.

Important limitation: the imported UN/LOCODE resource did not retain an edition or source-file checksum, and repository history does not identify one. It must therefore not be described as the current official edition. The [UNECE publications page](https://unlocode.unece.org/publications/) is the authority for the current production and pre-release datasets. A future refresh should replace this resource from a named publication and record its source checksum here.

## Licences and attribution

See `THIRD-PARTY-NOTICES.md`. Data licences are separate from the Apache-2.0 licence covering TradeRouter.Java's own code.

## Sea service allowance calibration

The per-corridor operational allowances in `traderouter/src/main/java/io/github/skuirrels/traderouter/movements/SeaServiceAllowance.java` are not embedded data, but they are derived from observations and are recorded here for the same reason.

- Fitted: 12 September 2026.
- Source: a freight forwarder's operational data warehouse, Databricks catalog `3105_qa_b_002_airsea`, schema `01_cw1_mart_20_dyn_lead_time`, mirrored locally to DuckDB (`odw.duckdb`) on 28 August 2026. Tables `dv_consol_legs` (11.8M legs) and `dv_consol_leg_events` (104M events).
- Measure: for each direct sailing on a load-port to discharge-port pair, the last non-estimate departure event to the last non-estimate arrival event, in UTC. Legs over 120 days or with arrival before departure were dropped. Legs departing 2025 and 2026 only, so the 2024 Red Sea diversions are excluded.
- Fraction: median observed hours divided by this library's travelling hours at 16 knots for the same pair, minus one, then the median across the lanes in a corridor.

| Corridor | Lanes fitted | Sailings | Fraction |
| --- | --- | ---: | ---: |
| asia-north-europe | CNSHA–NLRTM, CNSHA–DEHAM, CNSHA–FRLEH, VNSGN–NLRTM | 3,885 | 0.63 |
| asia-mediterranean | CNSHA–ITGOA, CNSHA–GRPIR | 656 | 1.33 |
| gulf-europe | AEJEA–NLRTM | 113 | 1.57 |
| transpacific | CNSHA–USLAX, VNSGN–USLAX | 1,194 | 0.12 |
| panama | CNSHA–USNYC | 439 | 0.24 |
| transatlantic | NLRTM–USNYC | 2,745 | 0.80 |

Before calibration every leg used 0.20. On the eleven fitted lanes the mean error against the observed median fell from 11.5 days to 1.3 days, and the worst lane from 26.7 days to 3.6. Corridors without observations keep 0.20. The mart records Shanghai as `CNSHA`; the library routes it as `CNSHG`.
