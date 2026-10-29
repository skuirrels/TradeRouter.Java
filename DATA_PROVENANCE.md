# Embedded data provenance

This file records the exact inputs and transformations used for the binary resources in `traderouter/src/main/resources/io/github/skuirrels/traderouter/data`.
SHA-256 values are hashes of the files as stored, not of their decompressed JSON.

## Maritime network and ports

- Distribution: [genthalili/searoute-py 1.6.0](https://github.com/genthalili/searoute-py/tree/1.6.0), commit `dde461382a60ff75f4577f6e06c411bc48cd7efc`.
- Source `marnet_searoute.geojson`: SHA-256 `111a82d949bb949c396a69a08828781314d3afe779c07e9754c3b2834dfca415`.
- Source `segment.geojson`: SHA-256 `56052b2ddd35de483ec00a29a7f0ffc37e162b0ee0249a9a435c8a8b2d8ffe0f`.
- Source `ports.geojson`: SHA-256 `8398cb9554a19cf37f53f932f3842f58fbd94fce4c8e2704c3f5a4205ea5977e`.
- Embedded `marnet.json.gz`: SHA-256 `7cb0ea865b4e080abf6caa8e28baef2146b4a644a37dad71fa80d001f4c9a074`; 9,708 nodes and 31,950 directed edges.
- Embedded `ports.json.gz`: SHA-256 `ccf97018a8d8c9cccafc08d69388dc0b483e39520f24bde94b36d6a8f8d2dca2`; 3,962 records.

`tools/build-marnet-data.py` combines the tagged Marnet network with its tagged antimeridian segments, converts every line to directed edges in both directions, tags the Volga–Don Canal, and calculates weights with the same mean-earth-radius Haversine formula used by the library. `tools/build-ports-data.py` retains every source port record, including duplicate codes. Both scripts verify their input hashes and write deterministic gzip files.

The Volga–Don Canal tag, `volga_don`, is the one passage not taken from searoute-py, added on 29 October 2026. The source line from the Caspian Sea at 47.8023° E, 45.1174° N to Taganrog Bay at 39.0431° E, 47.104° N carries no passage. The build script tags its seven segments from the vertex at 44.503624° E, 48.424983° N, where the lane leaves the Volga at Volgograd, to the vertex at 43.694824° E, 48.654066° N, where it reaches the Don near Kalach-na-Donu: 14 directed edges, 71.4 km. It fails unless both vertices lie on exactly one untagged source line and seven segments apart. Without the tag the script reproduces the earlier embedded file, SHA-256 `8d9be0fb7881b77f46d21bd066779cd4c561f8f70c80f02a5e381706ff7aa880`, byte for byte.

The searoute-py project credits [Eurostat SeaRoute](https://github.com/eurostat/searoute) as the origin of the maritime network. Eurostat describes that network as based on the Oak Ridge National Labs CTA global shipping lane network, enriched around European coasts with AIS-derived lines.

## Sea areas

- Publisher: [Natural Earth](https://www.naturalearthdata.com/), 1:50m physical vectors, `ne_50m_geography_marine_polys`. Natural Earth is in the public domain ([terms of use](https://www.naturalearthdata.com/about/terms-of-use/)).
- Source: [nvkelso/natural-earth-vector](https://github.com/nvkelso/natural-earth-vector) commit `117488dc88`, the last change to the file, 5 May 2022. `geojson/ne_50m_geography_marine_polys.geojson` SHA-256 `6fe58083e0cc5c7fad9e396970e28a8580bbd8770cfa4d1d7b5a34423e912f97`; 118 features.
- Embedded `sea-areas.json`: SHA-256 `758197d16cee52b6020e8edcc7b193cf6549a538b9aa840f469779b10e24927c`; the selection of 42 areas requested by Supply Chain Intelligence on 24 October 2026, and the Caspian Sea, added on 29 October 2026. The Caspian is a `sea` feature in this layer, not a lake, and is absent from `ne_50m_lakes`. Each entry gives the identifier, the English name in lower snake_case as passages are named, the Natural Earth class, the Wikidata item and the `ne_id`.
- Embedded `sea-area-polygons.json.gz`: SHA-256 `8a0c7d64d39327dd89fc367917850a70beb29c4a97c8f08b4a32aaea1c5c0f5e`; the 43 selected polygons, 21,399 vertices, keyed by `ne_id`.

`tools/build-sea-area-data.py` verifies the source hash, finds exactly one feature for each selected `ne_id`, and checks the selection's name, class and Wikidata item against the feature's `name`, `featurecla` and `wikidataid`. Names and classes are compared without case because the source spells `INDIAN OCEAN` and `SOUTHERN OCEAN` in capitals; the selection uses the title-case names. Polygons are copied unchanged, holes and antimeridian-split parts included, and written as deterministic gzip.

The Wikidata item is a cross-reference, not an identifier: the North and South Atlantic share Q97 and the North and South Pacific share Q98. The 1:50m data has no Gulf of Suez or Sea of Marmara, and on the five acceptance routes, Jeddah to Karachi, Singapore to Rotterdam, Shanghai to Los Angeles, Houston to Antwerp and Istanbul to Port Said, 0.3% to 26.2% of the route lies outside every selected polygon. Nothing fills those gaps.

## UN/LOCODE

- Publisher: [United Nations Economic Commission for Europe, UN/LOCODE](https://unlocode.unece.org/).
- Embedded `unlocode.json.gz`: SHA-256 `6e39b6efc925d804af5b304a4db2f701782cc4226fe31e2b97cd3b0bccfb052a`; 106,588 codes, of which 84,516 contain publisher coordinates.
- Embedded `unlocode-supplement.json.gz`: SHA-256 `c7f70e4113d9aa5fcec7430f4d778935cc26ab92ce91debc76efc860db6dbb70`, decompressed JSON SHA-256 `4036420a70c65867233126d8e43b59e72ca95e2a11cdf7129c589b0011b11d87`; the single attributed coordinate supplement. It fills UN/LOCODE rows that have no coordinate, and its reviewed rows also replace UNECE coordinates shown to be wrong. Five original hand-reviewed entries retain their cited sources.
- 147 of those reviewed rows correct UNECE coordinates that fall far outside their country (more than 1,500 km and five times the country's 90th-percentile distance from its median position: 172 codes on 28 September 2026, and one more, `ESVPE`, once Spain's other corrections narrowed its spread). 120 were checked against the GeoNames per-country dumps downloaded that day: a correction is kept only when the code's GeoNames `unlc` link, or else a same-country populated place or spot of the same name, gives exactly one position, and that position agrees with the UNECE latitude or longitude within 0.1°, or with both swapped. The remaining 27 were researched one by one, taking the code's GeoNames `unlc` link, the only place of that name in the country, the place at the UNECE latitude, or the airport for an airport code; `NGAKP` (Akpo) is the offshore field placed from Wikipedia, and `IDTZD` (Amurang) keeps the UNECE position with its longitude sign corrected, 8 km from GeoNames Teluk Amurang. The UNECE errors are mostly a dropped leading digit (Hedehusene, `DKHHS`, listed at 121.33°E, is at 12.20°E), a flipped sign (Luque, `PYLUQ`, 25.27°N for 25.27°S) or swapped latitude and longitude (Groß Köris, `DEGKO`). Each row's `source` names the place and its source ID, the UNECE coordinate it replaces and the evidence. 24 are correct as published: the Azores (including São Mateus, `PTSMT`, which stood out once Portugal's other corrections narrowed its spread), Guadeloupe (`FRG6E`), Saba (`NLSAB`), Road Town (`GBANO`) and Peapack, New Jersey (`GBPPC`, filed under GB). Three are left unchanged because several places share the name and none sits at the UNECE position: `ATKBA` (Kirchbach), `HNPBA` (Peña Blanca) and `TRESP` (Porsuk, published at 0,0).
- GeoNames source: [gazetteer downloads](https://download.geonames.org/export/dump/) dated 16 September 2026. `alternateNamesV2.zip` SHA-256 `745105840324aee7efea4dd50964b85c8266bddbc53f56a4950d9dcdaf2e4c50`; `allCountries.zip` SHA-256 `de79dd4dcfc2e6303e0121c0e3440426989ce38d23cf88ab5abc7d9d9bc4cf40`.
- The same `unlocode-supplement.json.gz` holds 17,766 GeoNames rows generated by `tools/build-unlocode-coordinate-supplement.py` from the exact source archives above. Each row records its GeoNames ID in `source`. The generator accepts only GeoNames `unlc` links to codes lacking UNECE coordinates, with matching country and normalized location name or recorded alternate name and exactly one distinct position; it preserves the hand-reviewed and corrected entries. The generated positions include Lightwater (`GBLGE`) at longitude `-0.67147`, latitude `51.34846`, GeoNames ID `7116406`.
- Coverage after the coordinate supplement: 102,287 of 106,588 embedded codes have coordinates. Another 4,301 remain unresolved by these sources: 1,776 have no explicit GeoNames `unlc` link, and 2,525 fail the same-country, name, unambiguous-position checks. The generated [review list](docs/unlocode-unresolved.csv) records every code, its reason and any linked GeoNames IDs (SHA-256 `e7b5b821899398ab4b6709cd36ec59eb1bb832aab36c536baf64cde4b49850a0`). These require further source review, a caller coordinate or a configured resolver. No coordinate is inferred from the code or a nearby place.
- Embedded `unlocode-seaport-supplement.json`: SHA-256 `3b515fb9e5ee7d178a1409d43c5503c05e55405b01c8fdc0c3eb4f4a3887a56e`; two attributed codes, BRALU and CADCN, whose UN/LOCODE function gains the sea-port flag. Each is a port-list record whose UN/LOCODE row records only a road terminal, confirmed as a deep-sea terminal by the source named on the entry.
- Embedded `unlocode-rail-supplement.json`: SHA-256 `467239df987a610205919761684d0cfd159739c86c2b9de5169a809137ca770d`; 20 codes whose UN/LOCODE function gains the rail-terminal flag. Each is a city code that UNECE publishes with neither the rail nor the multimodal function, confirmed on 30 September 2026 by an opened source naming an active freight rail intermodal terminal or yard in or serving the city: BNSF's intermodal facility list for eight US cities, and railroad, port authority, terminal operator, news or Wikipedia pages for the rest. The URL is recorded on each entry. Toronto (Vaughan), Monterrey (Salinas Victoria and Escobedo) and Savannah (Garden City) cite terminals in a neighbouring municipality. Tests check that no entry's published row already carries a rail or multimodal function and that each keeps its other functions.

- Embedded `port-code-aliases.json`: SHA-256 `456a4e27f55e62365b149010b59b236dd3d46731ed21cb34efa1ce3f87638742`; 61 official UN/LOCODE sea-port codes, each mapped to the port-list record held under a code UN/LOCODE lacks.

`tools/find-port-code-aliases.py` lists candidates from the embedded data: a port-list code absent from UN/LOCODE, paired with a same-country UN/LOCODE sea port that the port list does not hold, within 25 km, ranked by name similarity. On 14 September 2026 it produced 81 candidates at a similarity of 0.5 or more. 61 were kept after manual review because the names are the same place, including spelling variants such as Nauplia and Nafplion. 20 were rejected as different places, for example Londerzeel against Moerzeke and Mina Abdullah against Mina Al Ahmadi, or as the inland New Delhi depot. Each entry records both names and the distance.

Important limitation: the imported UN/LOCODE resource did not retain an edition or source-file checksum, and repository history does not identify one. It must therefore not be described as the current official edition. The [UNECE publications page](https://unlocode.unece.org/publications/) is the authority for the current production and pre-release datasets. A future refresh should replace this resource from a named publication and record its source checksum here.

The [UNECE 2025-1 production archive](https://unlocode.unece.org/publications/) was checked while preparing this supplement (SHA-256 `ad409fc7149b10f98d61190c34d9daf78b78bb8b31464cc66de1a89d09b01b5d`): its `GBLGE` row also has no coordinate. It is not silently substituted for the embedded edition; it has additional records and some coordinate strings require interpretation or validation before a full refresh.

## North American rail network

- Publisher: U.S. Department of Transportation, Bureau of Transportation Statistics, National Transportation Atlas Database: [North American Rail Network Lines](https://doi.org/10.21949/1528950), compiled from Federal Railroad Administration data; ArcGIS item `e143f436d4774402aa8cca1e663b1d24`, last modified 22 July 2026.
- Source snapshot: `tools/fetch-narn-data.py` paged all 302,771 arcs from the item's feature service on 30 September 2026, in WGS 84 to six decimal places, ordered by `OBJECTID`, keeping `OBJECTID`, `FRAARCID`, `FRFRANODE`, `TOFRANODE`, `COUNTRY`, `STATEAB`, `RROWNER1`, `NET` and `KM`. The hub's cached GeoJSON was not used because it dated from September 2024. Snapshot SHA-256 `ca4bb82ec226ea8005d352803500a46272c1627a2e1e9a049475e0e41f1aa440`.
- Embedded `rail-narn.json.gz`: SHA-256 `7743b4ed04986210d0e037fa6f7086251518c912e4f9718e90a9589530323e17`; 87,789 nodes, 91,000 edges stored once and loaded in both directions, 647 owning railroads, 272,068 km of track.

`tools/build-narn-data.py` keeps the 95,936 arcs coded `NET` `M`, the main sub-network. The other codes are industrial leads, minor track, sidings, yards, rail ferries, out-of-service, abandoned and removed lines, trails on former rail beds and transit-only or tourist lines. Every main-line arc runs from its `FRFRANODE` to its `TOFRANODE` and every node's arcs meet at one point, checked on this snapshot. Arcs are joined into 13,332 chains between junctions, line ends and changes of owner, `RROWNER1`. Each chain's line is simplified with Douglas-Peucker at 0.25 km and split so no segment exceeds 10 km, which keeps a graph node within 5 km along track of any point on the line. The chain's summed `KM` is shared across its segments in proportion to their great-circle length, so routed distance is the published track length, not the length of the simplified drawing. Positions are rounded to five decimal places. Connected networks shorter than 100 km, 927 arcs and 2,077 km that NARN links to the rest only through yard or industry track, are dropped so a location cannot snap onto a stranded fragment. Eight networks remain: the continental network of Canada, the United States and Mexico with 269,412 km, the Alaska Railroad, two Labrador and Quebec North Shore lines, the White Pass and Yukon, and three regional lines in Michigan, Oregon and Colorado that NARN joins to the continental network only through non-main track. The script verifies its input hash and writes deterministic gzip output.

Routing takes the shortest track distance whoever owns it, so a route can switch railroads where a real train would stay with one carrier or its interchange partners, and can cross commuter-owned track near a city-centre end point. It carries no timetable, speed limits, clearances or train paths.

## Road distance estimator calibration

The default built-in road estimator uses a distance-decay model rather than one circuity multiplier for every journey:

```text
estimated road km = max(straight-line km, 1.6173976178145946 × straight-line km ^ 0.9579701056959417)
```

The shape follows published road-network research rather than an assumed fixed number of access kilometres. Giacomin and Levinson, [Road network circuity in metropolitan areas](https://doi.org/10.1068/b130131p), found that short trips are more circuitous than long trips and modelled circuity with a distance-decay relationship. Chen and Chen, [Quantifying the relationships between network distance and straight-line distance](https://doi.org/10.1080/19475683.2021.1966503), independently found the same short-trip effect across 25 Chinese cities. The fitted exponent below makes the circuity ratio proportional to `distance^-0.0420298943`, so local access and network topology have a larger proportional effect on shorter legs without adding a route-specific correction.

Calibration was run on 15 September 2026 against the standard OSRM car profile in `ghcr.io/project-osrm/osrm-backend:26.8.0-debian`. Candidate endpoints came from the embedded UN/LOCODE data within the two sample coverage boxes. Pairs were sampled deterministically across straight-line distance bands; routes with either endpoint snapped more than 2 km were rejected. Gatwick–Heathrow was explicitly excluded. The accepted set contained 1,120 routes: 480 in the England sample and 640 in the South Africa sample. A deterministic hash split left 834 fitting routes and 286 held-out routes.

The OSM inputs were the dated Geofabrik snapshots resolved by the sample runner:

| Extract | Coverage box | SHA-256 of clipped PBF |
| --- | --- | --- |
| `england-260914` | `-1.0,50.8,1.8,52.2` | `249d5675edd7b6edae9dc86c972f9e0bf89faf41457bd23f89b1cfc5a85331b2` |
| `south-africa-260914` | `17.0,-35.0,29.5,-25.0` | `396e5b94c0e88ae8b1deea048dbfec38ce1a7df10a36550e74cae6305bf39fe3` |

Held-out results compare the former 1.3 multiplier with the selected distance-decay model:

| Held-out set | Model | MAE km | RMSE km | MAPE | Mean bias km |
| --- | --- | ---: | ---: | ---: | ---: |
| Combined, 286 routes | 1.3 multiplier | 22.50 | 51.45 | 9.19% | +11.29 |
| Combined, 286 routes | distance decay | 15.67 | 33.83 | 9.23% | +0.88 |
| England, 125 routes | 1.3 multiplier | 6.84 | 13.40 | 9.00% | -4.44 |
| England, 125 routes | distance decay | 6.10 | 12.36 | 9.34% | -2.46 |
| South Africa, 161 routes | 1.3 multiplier | 34.65 | 67.55 | 9.34% | +23.50 |
| South Africa, 161 routes | distance decay | 23.11 | 43.75 | 9.14% | +3.48 |

An access-allowance candidate of `C × distance + A × distance/(distance + S)` was also fitted. It produced a lower combined RMSE of 33.14 km but a worse MAE of 17.36 km and MAPE of 10.88%; it also performed worse than the old multiplier in the England holdout. It was therefore rejected. The selected model improved MAE and bias in both regions without encoding any location pair, country or sample route.

This remains a fallback, not a global road network. The calibration covers two regions and OSRM itself can choose a different distance depending on whether its profile optimises time, distance or road preference. Consumers requiring route-specific distance should configure a `RoadRouteProvider`; callers may replace the fallback through `MovementRequest.setRoadDistanceEstimator`.

## Licences and attribution

See `THIRD-PARTY-NOTICES.md`. Data licences are separate from the Apache-2.0 licence covering TradeRouter.Java's own code.

## Sea service allowance calibration

The per-corridor operational allowances in `traderouter/src/main/java/io/github/skuirrels/traderouter/movements/SeaServiceAllowance.java` are not embedded data, but they are derived from observations and are recorded here for the same reason.

- Fitted: 12 September 2026.
- Source: a proprietary dataset of observed sailings, extracted on 28 August 2026 and not redistributed.
- Measure: for each direct sailing on a load-port to discharge-port pair, the last non-estimate departure event to the last non-estimate arrival event, in UTC. Legs over 120 days or with arrival before departure were dropped. Legs departing 2025 and 2026 only. The data records no routing, so it cannot show whether a sailing went through Suez or round the Cape; the Cape comparison below suggests most Asia–Europe sailings in this period went round the Cape.
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

### Cape corridors

- Fitted: 14 September 2026, from the same proprietary dataset, extracted again since the first fit. Nothing from the dataset is embedded or read at runtime; only the resulting fractions are in the code.
- Measure: as above. The re-run reproduced the first fit closely, for example CNSHA–NLRTM 45.5 days from 2,092 sailings against 45.6 days, and 3,969 Asia–North Europe sailings against 3,885.
- Denominator: this library's travelling time at 16 knots for a movement leg with `Northwest`, `Suez` and `Panama` restricted, so the leg sails round the Cape of Good Hope.
- Fraction: median observed hours divided by that Cape travelling time, minus one, then the median across the lanes in a corridor.

| Corridor | Lane | Sailings | Observed days | Cape travel days | Lane fraction | Corridor fraction |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| asia-north-europe-cape | CNSHA–NLRTM | 2,092 | 45.5 | 36.09 | 0.261 | 0.22 |
| | CNSHA–DEHAM | 1,458 | 44.0 | 36.76 | 0.197 | |
| | CNSHA–FRLEH | 347 | 40.9 | 35.58 | 0.150 | |
| | VNSGN–NLRTM | 72 | 40.7 | 32.52 | 0.252 | |
| asia-mediterranean-cape | CNSHA–ITGOA | 547 | 53.6 | 35.53 | 0.508 | 0.37 |
| | CNSHA–GRPIR | 115 | 45.9 | 37.02 | 0.240 | |
| gulf-europe-cape | AEJEA–NLRTM | 114 | 42.2 | 28.86 | 0.462 | 0.46 |

The Asia–North Europe Cape fraction of 0.22 is close to the unfitted default and far below the Suez-route 0.63. Observed times therefore match Cape routing plus a normal service allowance, and the Suez-route fractions absorb the Cape detour. The two Mediterranean lanes disagree, so each is about five days from the corridor median. A leg the router sends through Panama between East Asia and Europe, which happens when Suez is closed and Panama is not, takes the North Europe Cape fraction; its travelling time is within a day of the Cape route. The `panama` corridor now requires one end in the Americas.
