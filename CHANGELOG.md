# Changelog

All notable changes to TradeRouter.Java are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Version numbers follow TradeRouter.Net, whose [changelog](https://github.com/skuirrels/TradeRouter.Net/blob/main/CHANGELOG.md) records the same routing, data and emissions changes.

## [Unreleased]

Ported from TradeRouter.Net 2.13.0 at `ca02c9a`, which includes 2.11.0, 2.11.1 and 2.12.0: the UNECE 2025-1 additions (`ca0e914`), the added-code coordinate fill (`2ca4d03`), the Charleston, Illinois correction (`fc92b75`), the fallback warnings in the movement text report (`8ea8ada`, `52e151a`) and the airport-named UN/LOCODEs and Ningbo codes (`094b7cf`, `a9a4980`). Java skips 2.11.0 to 2.12.0. Every embedded data file is byte-identical to TradeRouter.Net 2.13.0.

### Added

- `MovementResult.toText()` prints a `Leg <n> warning = <distance_warning>` line after the summary line, before the footnotes, for each leg whose distance fell back: a road leg estimated because its road-route provider returned `UNAVAILABLE` or `OUTSIDE_COVERAGE`, and a rail leg the rail network could not route. A provider outage, such as a key that Google Routes rejects with HTTP 403, no longer looks like an ordinary estimate. The label is padded to the width of the other footnote names, line breaks in the warning become spaces and trailing whitespace is dropped, and the lines appear with or without explanations. Reports with no fallback are unchanged, including those with ordinary road estimates, whose caveat stays in the distance basis footnote. The GeoJSON shape is unchanged.
- `TradeRouterProperties.isDistanceFallback()` and `setDistanceFallback(boolean)` say whether `distance_warning` reports such a fallback. They are not serialised. TradeRouter.Net keeps this flag internal; Java makes it public because the engine, the GeoJSON properties and the text report live in different packages.
- 9,434 UN/LOCODEs that the embedded list lacked, from the UNECE 2025-1 release, 8,177 of them with a coordinate, so 112,023 of 116,022 codes resolve without a caller coordinate. Examples are Allach (`DEAL9`), Chonburi (`THCHO`), Tay Ninh (`VNTNT`) and Malmesbury (`ZAZCO`). The merge is add-only: existing codes keep their name, coordinate and functions, and codes the port list or port-code aliases use are left alone, so every code that resolved before resolves as it did. 55 added coordinates that fall far outside their country, such as Bystrocice (`CZBYT`) at 171°E, are held back for review.
- `tools/merge-unlocode-release.py` repeats the merge from a pinned UNECE release zip.
- Coordinates for 1,509 more UN/LOCODEs that UNECE publishes without one, so 113,532 of 116,022 codes now resolve. 1,485 come from `tools/fill-added-unlocode-coordinates.py`: GeoNames features linked to the code, Wikidata items confirmed by GeoNames, or the only GeoNames place of the code's name in its country, each confirmed in its UNECE subdivision where it has one. 24 more were researched by hand, such as Yichang port (`CNYCG`) and the Taba Border Crossing (`ILTAB`). Existing supplement rows are unchanged, and codes the port list uses, and the original sea-port and airport codes reviewed earlier, are left alone. Of the 55 coordinates the 2025-1 merge held back, 45 are now placed from GeoNames.

### Fixed

- 33 UN/LOCODEs that UNECE records as named airports, such as Ningbo Lishe (`CNNGB`), Shanghai Hongqiao (`CNSHA`), Qingdao Jiaodong (`CNTAO`), Tianjin Binhai (`CNTSN`) and John F. Kennedy (`USJFK`), now resolve to the airport rather than to the port list's record under the same code, 2.5 to 45 km away, which is the city or its port filed under the airport's IATA code. Each now lies within 2 km of its airport. The resolver lets UN/LOCODE win when it records the code with the airport function, without the sea-port function, under a name containing "Apt" or "Airport", and has a position for it. A sea leg is still rejected at these codes, because UN/LOCODE records no sea-port function for them, and a caller who declares the sea-port function through `MovementRequest.addLocationFunctions` still gets the port-list record. Port-to-port `TradeRoutes.calculate` still reads the port list alone and is unchanged.
- Ningbo (`CNNBO`) now carries the rail-terminal and airport functions that UNECE 2025-1 lists, besides the sea port, so it can end a rail or air leg. The rail supplement gains the entry and a new airport supplement, `unlocode-airport-supplement.json`, holds the airport function; the embedded UNECE row is unchanged.
- `TradeRoutes.calculate` accepts Ningbo's own code: `CNNBO` and 21 other official Chinese and Thai city or port codes, such as Tianjin Pt (`CNTNG`), Xiamen (`CNXAM`), Dalian Pt (`CNDAG`) and Krabi (`THKRV`), are aliased to the port-list record that the port list files under the city's airport code, within 10 km and under the same name. They threw "not found" before. `tools/find-port-code-aliases.py` now lists these candidates too.
- A road leg's `distance_warning` after a provider fallback no longer doubles the full stop when the provider's message already ends with one, as most built-in Google Routes and OSRM messages do: `osrm returned Unavailable: The OSRM request timed out. Estimated ...` rather than `timed out.. Estimated`. The message is trimmed, a full stop is added only when it does not already end with `.`, `!` or `?`, and a blank message reads `no reason supplied.`
- Charleston, Illinois (`USILC`) now resolves to Charleston, the Coles County seat, rather than St. Charles, Illinois, 269 km north. The generated GeoNames row is replaced by a reviewed one, because the St. Charles feature in GeoNames also carries the code.

### Documentation

- The `MovementRequest.getAdditionalLocationFunctions()` Javadoc and the README's location-functions section no longer say declared functions never change how a code resolves: declaring the sea-port function keeps the port-list record for an airport-named code. The TradeRouter.Net 2.13.0 README and XML documentation still say they do not.
- The README movement example shows the rail leg's warning line, and the `distance_warning` property, road-provider fallback and rail-routing sections say when the report prints it.
- `DATA_PROVENANCE.md`, the README coverage figures and `docs/unlocode-unresolved.csv` describe the merged list and the new supplement rows. The README's data section also gives the supplement's new row count, 21,005, which the TradeRouter.Net 2.11.0 README still gives as 19,496. `docs/waypoints-and-choke-points.md` now gives the current coverage, 113,532 of 116,022 codes, in place of 102,703 of 106,588.

## [2.10.0] - 2026-11-13

Ported from TradeRouter.Net 2.10.0 at `c1328f0`: the GLEC Framework v3.3 change (`ec082c1`) and sample example 18 (`c8d2d71`).

### Added

- `EmissionFactors.glecV32Defaults()` returns the GLEC Framework v3.2 values, for reporting that must stay comparable with a baseline calculated before v3.3. Pass it to `MovementRequest.setEmissions`.

### Changed

- `EmissionFactors.glecDefaults()` and a new `EmissionFactors` now carry the [GLEC Framework v3.3](https://smartfreightcentre.org/news/13311209) (Smart Freight Centre, October 2026) Module 2 defaults, so default CO2e figures for sea and air legs change.
- Sea: 73.4 g per TEU-km from Table 18, industry-average dry container, end-user intensity, up from 71.7, or 7.34 g per tonne-km at 10 t per TEU, up from 7.17.
- Air, Table 1, including the +95 km distance adjustment: freighter 1,512 g per tonne-km under 1,500 km and 638 from 1,500 km (were 1,516 and 608), belly freight 1,332 and 930 (were 1,239 and 936), service unknown 1,413 and 799 (were 1,363 and 788).
- Road, 125 g, and rail, 30.7 g in Europe and 16.1 g in North America, are unchanged in v3.3.

### Sample

- Sample example 18, ported from TradeRouter.Net 2.10.0 (`c8d2d71`), sends two movements that share the Gatwick to Heathrow leg through `CachingRoadRouteProvider` in front of OSRM, and reports how many OSRM calls the cache saved. It needs `TRADEROUTER_OSRM_URL`, `TRADEROUTER_ROAD_CACHE_PARTITION` and `TRADEROUTER_ROAD_CACHE_DAYS`, which `run-with-osrm.sh` sets. The store is an in-memory `DistributedCache` that lasts for the run. The README describes it under OSRM road routing and the sample section.

## [2.9.1] - 2026-11-04

A Java-only release. Routes, data and output are unchanged from 2.9.0 and still match TradeRouter.Net 2.9.0 at `09a241c`.

### Documentation

- The README and Javadoc no longer describe the terms of road-route providers, such as Google Maps Platform caching and display rules or OpenStreetMap attribution. Complying with a provider's terms is left to the application. Releases 2.2.0 to 2.9.0 still include that text.

## [2.9.0] - 2026-11-03

Ported from TradeRouter.Net 2.9.0 at `09a241c`.

### Road-route caching

- New package `io.github.skuirrels.traderouter.caching`, in the `traderouter` jar itself, since unlike the separate .NET `TradeRouter.Net.Caching` package it needs no dependency. `CachingRoadRouteProvider` wraps any `RoadRouteProvider` and serves repeated road legs from a store, keyed on a caller-chosen partition and the endpoints rounded to `coordinateDecimals`. Successful routes are kept for `timeToLive` from the time they were fetched, `NO_ROUTE` and `OUTSIDE_COVERAGE` results only when `negativeTimeToLive` is set, and `UNAVAILABLE` results never. A store failure is reported to `onCacheError` and the leg is routed without the cache. Cancelling the returned future cancels the store or provider call in progress.
- `DistributedRoadRouteCache` stores results as JSON entries with an absolute expiry in any `DistributedCache`, a two-method interface to adapt Redis, a SQL table or an in-process cache to. Implement `RoadRouteCache` to use another store. Keys and entries are those TradeRouter.Net writes, so Java and .NET services can share one store.
- The caching provider caches whatever the wrapped provider returns. Google Maps Platform terms do not allow Routes API results to be cached, so do not wrap `GoogleRoutesRoadRouteProvider` in it.
- Unlike TradeRouter.Net 2.9.0, a stored success with a positive distance and a zero or negative duration is treated as unusable and routed again, as the engine would reject it, and a provider result the engine would reject is not stored at all, so later reads do not report it as a cache fault.

## [2.8.0] - 2026-10-30

Ported from TradeRouter.Net 2.8.0 at `6a4d4c6`.

### Location data

- 1,143 airports have coordinates, so movements and `locate` can name them by code. UNECE publishes none for them, and air legs to or from them previously threw. They include Narita (`JPNRT`), Haneda (`JPHND`), Kansai (`JPKIX`), Dallas-Fort Worth (`USDFW`), Dulles (`USIAD`), Detroit (`USDTW`), Beijing Capital (`CNPEK`), Malpensa (`ITMXP`), Linate (`ITLIN`), Arlanda (`SEARN`), Stansted (`GBSTN`), Montreal (`CAYUL`), Vancouver (`CAYVR`), Jakarta (`IDCGK`) and Addis Ababa (`ETADD`). `tools/fill-airport-coordinates.py` places each at the OurAirports airport that is the only open one in its country carrying its IATA code, when the names share a distinctive word. 271 airport codes remain without a position.
- 20 of the 27 codes that 2.6.1 rejected as port-list namesakes now resolve to their own UN/LOCODE place, because they are airports: Latrobe (`USLBE`) is at Arnold Palmer Regional Airport in Pennsylvania, Madison (`USMDN`) in Indiana, and Shantou (`CNSWA`) and Wuxi (`CNWUX`) at their airports. Seven still throw.

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

[2.10.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.10.0
[2.9.1]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.9.1
[2.9.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.9.0
[2.8.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.8.0
[2.7.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.7.0
[2.6.1]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.6.1
[2.4.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.4.0
[2.2.1]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.2.1
[2.2.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.2.0
[2.1.1]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.1.1
[2.0.0]: https://github.com/skuirrels/TradeRouter.Java/releases/tag/v2.0.0
