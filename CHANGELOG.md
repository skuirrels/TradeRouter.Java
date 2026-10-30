# Changelog

All notable changes to TradeRouter.Java are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Version numbers follow TradeRouter.Net, whose [changelog](https://github.com/skuirrels/TradeRouter.Net/blob/main/CHANGELOG.md) records the same routing, data and emissions changes.

## [2.7.0] - 2026-10-30

Ported from TradeRouter.Net 2.7.0 at `a8b1f8b`.

### Sea routing

- The Kiel Canal is a passage: `Passage.KIEL`, `kiel`, as Eurostat SeaRoute names it. searoute-py has no lane through the canal, so routes between the Elbe or the North Sea and the Baltic went round Skagen. `tools/build-marnet-data.py` now adds Eurostat SeaRoute's canal line from Brunsbüttel to Kiel-Holtenau, 96 km, with Eurostat's approach lanes up and down the Elbe and out to Kiel Bay. It is open by default, so those routes now use it and report it in `traversed_passages`: Hamburg to Fredericia is 398 km instead of 956 km, Hamburg to Szczecin 522 km instead of 1,178 km, Rotterdam to Gdansk 1,250 km instead of 1,640 km and Bremerhaven to Stockholm 1,155 km instead of 1,656 km. Listing it in `setRestrictions` restores the route round Skagen with its earlier length. Routes that do not use it are unchanged.
- Eurostat's lanes to Husum, Tönning and Eckernförde are added too. These ports had no lane, and without one they would snap to the canal. Twelve ports beside the canal now snap to an added lane point nearer them: Brunsbüttel, Holtenau, Kiel, Laboe, Rendsburg, Eckernförde, Schleswig, Kappeln, Husum, Tönning, Büsum and Cuxhaven. Their routes now include the lane to the port, so a route that does not use the canal can be longer: Husum to Rotterdam is 572 km instead of 479 km, and Kiel to Gothenburg 421 km instead of 406 km. With it closed, places on the canal have no sea route: Rendsburg, and in movements UN/LOCODE sea ports such as Audorf (`DEAUO`), Budelsdorf (`DEUSF`) and Obereiderhafen (`DEOEX`).
- The lane network has 9,748 nodes and 32,034 directed edges, up from 9,708 and 31,950. The earlier nodes and edges are unchanged.

## [2.6.1] - 2026-10-30

Ported from TradeRouter.Net 2.5.0, 2.6.0 and 2.6.1 at `8211bc4`.

### Location resolution

- A code whose port-list records are duplicated no longer throws in movements, `locate` or port-to-port `calculate`. Resolution takes the record nearest the code's UN/LOCODE position, or, without one, the terminal record when the records lie within 50 km of each other. Twelve codes had no UN/LOCODE position and threw `IllegalStateException` for every mode, including Nhava Sheva (`INNSA`), Davao (`PHDVO`) and Palembang (`IDPLM`). Port-to-port `calculate` also threw for the 26 duplicated codes that do have a position, such as Vancouver (`CAVAN`). A code whose records lie further apart, with no UN/LOCODE position, now throws `IllegalArgumentException` rather than `IllegalStateException`.
- A code that UN/LOCODE lists without a position no longer falls back to a port-list record for a different place. With no position to compare, the names must agree, allowing for spelling variants such as Haerbin and Harbin. Latrobe (`USLBE`) had resolved to Laguna Beach, California, and Madison (`USMDN`) to Mandan, North Dakota. 27 codes now throw an `IllegalArgumentException` naming both places, 21 of them in the United States. Shantou (`CNSWA`) and Wuxi (`CNWUX`) are among them, because UN/LOCODE files those codes as airports; the ports are `CNSTG` and `CNWXS`.
- A code no longer resolves to a same-named port-list entry more than 50 km from its UN/LOCODE position; the UN/LOCODE position wins. The port list had filed namesakes under 277 codes over 100 km away, 194 of them in the United States, so Pittsburgh (`USPIT`) resolved to Pilottown, Louisiana, Phoenix (`USPHX`) to Louisiana, Nashville (`USBNA`) and Birmingham (`USBHM`) to Michigan, and Monterrey (`MXMTY`) to Chiapas. Rail legs to those codes now route to the right city. The resolved `port()` is null for these codes. Port-to-port `calculate` still uses the port list and is unchanged.
- 31 codes whose UN/LOCODE or GeoNames position was wrong where the port list was right are corrected from the World Port Index or GeoNames, so they keep resolving to the port list: for example Pago Pago (`ASPPG`), High Point (`USHPZ`) and Rafina (`GRRAF`).

### Location data

- Tanger Med (`MAPTM`) has a coordinate, so movements can name it by code. Port-to-port `calculate("MAPTM", ...)` still needs coordinates, because the port list lacks it.
- Montreal (`CAMTR`) is a rail terminal in the rail supplement and gains a UN/LOCODE coordinate at the port; the port list still places it for movements, so routes are unchanged.
- 414 more sea ports have coordinates, from the NGA World Port Index, Wikidata and GeoNames, built by `tools/fill-sea-port-coordinates.py` from pinned sources. 3,885 codes still have no position.

### Rail timing

- Rail legs can carry time allowances on top of distance divided by speed, reported in `transit_hours`: `MovementRequest.setRailOriginTerminalHours` and `setRailDestinationTerminalHours` for terminal dwell, `setRailInterchangeHours` for each interchange between Class I railroads, and `setRailBorderCrossingHours` for each crossing of the US–Canada or US–Mexico border. All default to zero, so rail times are unchanged unless a request sets them. Negative or non-finite values are rejected.
- Rail legs report `rail_terminal_hours`, `rail_interchanges`, `rail_interchange_hours`, `border_crossings` and `border_hours`, and movements report `total_rail_terminal_hours`, `total_rail_interchange_hours` and `total_border_hours`. The same figures are on `LegResult` and `MovementResult`, and the text summary names them when a request sets them.
- Interchanges are estimated by `RailServiceAllowance.countClassIInterchanges` as the number of Class I railroads owning at least 1,000 km of a routed leg, less one. `MovementRequest.getRailInterchangeCounts()` sets the count for a leg when it is known, including great-circle and supplied rail legs. Border crossings are counted from the UN/LOCODE country of each end.

### Rail terminals

- 77 more codes are rail terminals in the rail supplement, 97 in all: 44 more US terminals, 21 Canadian hubs and US suburban ramps, 11 Mexican terminals and Montreal. Each cites a page naming the terminal. They can now be named as stations and joined by rail legs without `addLocationFunctions`.

## [2.4.0] - 2026-10-29

Ported from TradeRouter.Net 2.3.0 and 2.4.0 at `aa137e1`.

### Sea routing

- The Caspian Sea is a sea area: `SeaArea.CASPIAN_SEA`, `caspian_sea`, Wikidata Q5484, from the same pinned Natural Earth 1:50m marine polygons. Routes between Caspian ports, and routes leaving it over the Volga–Don link, now report it in `traversed_sea_areas`.
- The Volga–Don Canal is a passage: `Passage.VOLGA_DON`, `volga_don`. It is the only lane out of the Caspian Sea, so routes leaving the Caspian now report it in `traversed_passages`, and listing it in `setRestrictions` closes it. It is open by default, so routes are unchanged.
- `tools/build-marnet-data.py` tags the canal stretch of the untagged searoute-py line between the Caspian Sea and Taganrog Bay. The lower Volga and lower Don stay untagged, so Astrakhan and Rostov-on-Don keep their routes to their own seas when the canal is closed.

## [2.2.1] - 2026-10-27

A Java-only release. Routes, data and output are unchanged from 2.2.0 and still match TradeRouter.Net 2.2.0 at `beb64c8`.

### Build

- The library now targets Java 17 instead of Java 21, so applications on Java 17 can use it. Building the project still needs JDK 21, and the sample and benchmarks still target Java 21. This change is Java-only.

## [2.2.0] - 2026-10-26

Ported from TradeRouter.Net 2.2.0 at `beb64c8`.

### Road routing

- `GoogleRoutesRoadRouteProvider` routes road legs through the Google Maps Platform Routes API, as an alternative to `OsrmRoadRouteProvider`. It sends `DRIVE` requests without live traffic, so calls stay on the Compute Routes Essentials SKU, and reports an endpoint snapped beyond `maximumSnapDistanceMeters` as `OUTSIDE_COVERAGE`. Like the OSRM adapter, it uses the JDK `HttpClient` and takes an optional per-request timeout.
- `RoadRouteProvider` documents the contract for custom providers: which status to return, how each `RoadRoutingMode` treats it, and the thread-safety requirement.
- Sample examples 16 and 17 route road legs with Google when `TRADEROUTER_GOOGLE_MAPS_API_KEY` is set.
- The request timeout of `OsrmRoadRouteProvider` and `GoogleRoutesRoadRouteProvider` now covers the whole exchange, including the response body, which the JDK 21 request timeout leaves untimed. This matches the .NET `HttpClient.Timeout`. Both providers also reject a base URI without a host when they are constructed. These two changes are Java-only.

### Documentation

- The README compares the OSRM, Google and custom road providers, covers Google's caching and display terms, and shows a custom provider.

## [2.1.1] - 2026-10-24

Ported from TradeRouter.Net 2.1.1 at `e80f054`.

### Sea routing

- `setReturnSeaAreas(true)` reports the named sea areas a route passes through as `traversed_sea_areas`, in the order it enters them, with `enter_km` and `exit_km` from the start of the route. Each sea leg of a movement carries it too, counted from the start of the leg.
- 42 seas, gulfs, straits and oceans from Natural Earth 1:50m marine polygons, as `SeaArea` constants with `SeaArea.getCatalogue()` listing names, Wikidata items and Natural Earth IDs. `SeaAreaInfo` and `SeaAreaTraversal` are records, and `SeaArea.traverse` finds the areas along any continuous path.

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

[2.7.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.7.0
[2.6.1]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.6.1
[2.4.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.4.0
[2.2.1]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.2.1
[2.2.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.2.0
[2.1.1]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.1.1
[2.0.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.0.0
