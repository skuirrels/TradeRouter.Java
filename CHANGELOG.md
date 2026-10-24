# Changelog

All notable changes to TradeRouter.Java are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Version numbers follow TradeRouter.Net, whose [changelog](https://github.com/skuirrels/TradeRouter.Net/blob/main/CHANGELOG.md) records the same routing, data and emissions changes.

## [2.1.1] - 2026-10-24

Ported from TradeRouter.Net 2.1.1 at `e80f054`.

### Sea routing

- `setReturnSeaAreas(true)` reports the named sea areas a route passes through as `traversed_sea_areas`, in the order it enters them, with `enter_km` and `exit_km` from the start of the route. Each sea leg of a movement carries it too, counted from the start of the leg.
- 42 seas, gulfs, straits and oceans from Natural Earth 1:50m marine polygons, as `SeaArea` constants with `SeaArea.getCatalogue()` listing names, Wikidata items and Natural Earth IDs. `SeaAreaInfo` and `SeaAreaTraversal` are records.

### Data

- The UN/LOCODE coordinate supplement is embedded as `unlocode-supplement.json.gz`. Its decompressed JSON is unchanged.
- `sea-areas.json` and `sea-area-polygons.json.gz` are byte-identical to TradeRouter.Net, with hashes in `DATA_PROVENANCE.md`.

## [2.0.0] - 2026-10-15

First release on Maven Central, ported from TradeRouter.Net 2.0.0 at `de93a0e`.

### Sea routing

- Shortest sea route between two UN/LOCODE port codes or coordinates on the Marnet network from searoute-py 1.6.0, returned as RFC 7946 GeoJSON.
- Bidirectional Dijkstra and A* on a compressed sparse row graph, with a priority queue that mirrors the .NET `PriorityQueue` so equal-cost routes break ties identically.
- Antimeridian crossings split into `MultiLineString`; routes with no path return a feature with null geometry.
- Canals and straits can be avoided by name. The Northwest Passage stays closed unless `setAllowNorthwest(true)`.
- Inland points snap to the nearest terminal through a spherical KD-tree, and preferred ports can be weighted per area.
- An instance `DefaultTradeRouterEngine`, implementing `TradeRouterEngine`, and the static `TradeRoutes` facade.

### Locations

- The UNECE UN/LOCODE list is embedded and resolves every movement location by code.
- A sourced coordinate supplement fills 17,771 codes UNECE publishes without coordinates, and corrects 147 UNECE coordinates that fall far outside their country.
- A reviewed alias list maps 61 official UN/LOCODEs to the differently coded record in the port list.
- Sea-port and rail-terminal function supplements confirm ports and 20 freight rail terminals that UNECE under-records.
- Requests can declare additional location functions for validation, and immutable sets are accepted.

### Multi-leg movements

- A typed `MovementPlan` API routes pickup, main and delivery legs by sea, road, rail and air, with totals, a GeoJSON `FeatureCollection` and a text report matching .NET.
- A modelled minimum transit time: 16 knot sea speed, port dwell, connection allowances and a sea service allowance fitted per trade corridor, including Cape corridors.
- Road legs use a calibrated distance-decay estimate by default, an imported route, or a `RoadRouteProvider`.
- `OsrmRoadRouteProvider` calls OSRM through the JDK `HttpClient` from `calculateMovementAsync`, which returns a cancellable `CompletableFuture`.
- North American rail legs route on the BTS North American Rail Network when both ends lie within `railNetworkSnapKm`. Other rail legs use great-circle distance with a `distance_warning`, and supplied rail routes can be imported.
- Routes and legs report distance, duration and geometry provenance.

### Emissions

- Well-to-wheel CO2e per leg and in totals. Every factor is a GLEC Framework v3.2 Module 2 default.
- Sea: 71.7 g per TEU-km, or 7.17 g per tonne-km at the GLEC average of 10 t per TEU. Legs are charged per TEU when a container count is given.
- Road: 125 g per tonne-km.
- Rail: 16.1 g per tonne-km on the North American rail network and 30.7 g elsewhere.
- FCL road and rail legs are charged per container at no less than 10 t per TEU.
- Air: Table 1 factors for the leg's `AirCargoService`, freighter, belly freight or unknown, in bands under and from 1,500 km. The service is reported on air legs as `air_service`.

### Packaging

- Targets Java 21 with no runtime dependencies. JSON is read and written by the built-in `io.github.skuirrels.traderouter.json` package, which rejects nesting deeper than 64 levels.
- All data is embedded as compressed JSON, byte-identical to TradeRouter.Net, with hashes in `DATA_PROVENANCE.md`.
- Published as `io.github.skuirrels:traderouter` with sources and Javadoc jars, an `Automatic-Module-Name` of `io.github.skuirrels.traderouter`, reproducible jar timestamps, and the licence, third-party notices and data provenance under `META-INF`. `docs/releasing.md` covers signing and the Central Portal.
- A JUnit 5 suite asserts the .NET reference figures. A console sample, an OSRM runner script and JMH benchmarks ship as separate modules.

[2.1.1]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.1.1
[2.0.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.0.0
