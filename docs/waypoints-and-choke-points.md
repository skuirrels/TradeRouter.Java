# Waypoints and choke points in TradeRouter.Java

This document describes every kind of waypoint that can appear in a route produced by TradeRouter.Java, and every choke point (canal or strait) that the routing network knows about. All counts and distances below were measured against the datasets embedded in the library on 10 September 2026.

## 1. Transport modes covered

TradeRouter.Java routes deep-sea shipping on the Eurostat Marnet network. Multi-leg movements may also contain road, rail and air legs. Road distance comes from an optional road-network provider or a labelled built-in distance-decay circuity estimate. Rail legs in the United States, Canada and Mexico follow the embedded North American rail network; other rail legs and air legs use great-circle distance.

| Mode | Routed | Network | Leg geometry | Duration basis | Choke points |
|---|---|---|---|---|---|
| Sea | Yes | Marnet, 9,708 nodes, 31,950 directed edges | Network path | `speedKnots`, default 16, plus 24 h port dwell per leg end in movements | 13 tagged passages, listed in section 3 |
| Road | When a `RoadRouteProvider` is configured | Configured provider, for example OSRM | Network path when returned; otherwise a straight display line | Routed or estimated distance divided by `speedsKmh[ROAD]`, default 60; provider/imported duration by explicit opt-in | None |
| Rail | In the United States, Canada and Mexico | Main line of the BTS North American Rail Network, 87,789 nodes, 91,000 track edges | Network path when both ends are within `railNetworkSnapKm`; otherwise a straight line, 2 points | `speedsKmh[RAIL]`, default 80 | None |
| Air | No | None | Straight line, 2 points | `speedsKmh[AIR]`, default 800 | None |
| Inland waterway | No | None | Not supported as a mode | n/a | None |

Straight-line elements still exist where the leg joins an inland origin or destination to its nearest maritime-network point, and for air legs, rail legs outside the rail network, or estimated road legs without network geometry. They are display connections and are not checked against land. An estimated road leg reports its circuity-adjusted distance separately from that straight display geometry.

Movement legs are normally built with the fluent `MovementPlan` API from typed `Waypoint` values and `TransportMode` constants. Each destination automatically becomes the next leg's origin, so every location code is stated once; the plan also enforces that Pickup is first and Delivery is last. `MovementParser` remains available only when an integration already supplies human-written leg lines. Known UN/LOCODE functions are checked against the declared waypoint types and against sea, rail and air modes. Location codes resolve against caller-supplied coordinates first, then the embedded port list when UN/LOCODE agrees on the name, then the embedded UN/LOCODE list where it carries coordinates, then an `LocationResolver`. The embedded list and its coordinate supplement resolve 102,287 of 106,588 codes; the remaining 4,301 need caller coordinates or a resolver. A sea leg with no route raises an error naming the leg.

## 2. Waypoint types

A route is a GeoJSON `LineString`, or a `MultiLineString` split at the antimeridian. Every position in it is one of the waypoint types below. The waypoint type is not written into the geometry; it follows from position and options.

| # | Waypoint type | Source | Count in data | When it appears | Position in the line |
|---|---|---|---|---|---|
| 2.1 | Network node | Marnet vertex | 9,708 | Always | Interior, and the ends unless 2.3 or 2.4 apply |
| 2.2 | Snapped endpoint | Nearest network node to a request point | Chosen per request | Always | First and last network node |
| 2.3 | Requested origin and destination | Caller's coordinates | 2 per request | `setAppendOriginDestination(true)` | Very first and very last |
| 2.4 | Port | World ports database | 3,962 records | Port-code overload, or `setIncludePorts(true)` | Between 2.3 and 2.2 at each end |
| 2.5 | Preferred port in an area | Caller's `AreaFeature` polygon and `PortProps` weights | Caller-defined | `PortParameters.setPortsInAreas*` | As 2.4, one route per port |
| 2.6 | Custom port | `PortProps` with an unknown code | Caller-defined | As 2.5 | As 2.4 |
| 2.7 | Antimeridian seam node | Marnet vertices on the 180th meridian | 39 seam edges | Trans-Pacific and Arctic routes | Interior |
| 2.8 | Degenerate result | Engine | n/a | Same snapped node, or no path | Two-point line, or null geometry |

### 2.1 Network node

A vertex of the Marnet shipping network, stored as longitude and latitude. Nodes are joined by edges whose weight is the great-circle distance in kilometres. Edge lengths range from 0 km on seam edges to 3,611 km on open-ocean legs, with a median of 84 km.

Node connectivity tells you what a node is for:

| Degree | Nodes | Role |
|---|---|---|
| 1 | 701 | Dead-end spur into a port approach or a fjord |
| 2 | 3,176 | Shape point along a lane |
| 3 | 810 | Junction where a spur leaves a lane |
| 4 | 4,048 | Grid crossing in open ocean |
| 5 to 17 | 973 | Hub where several lanes converge, for example off Singapore or Gibraltar |

Every interior coordinate of a route is a network node. The path is the sequence of nodes chosen by bidirectional Dijkstra or A*.

### 2.2 Snapped endpoint

The engine does not add the caller's coordinates to the network. It finds the geographically nearest network node with a three-dimensional unit-sphere KD-tree and searches between the two snapped nodes. Spherical chord distance has the same nearest-neighbour ordering as great-circle distance, including across the date line and near the poles. A point well inland can still be far from any maritime lane; inspect the snap distance when that matters.

Without `AppendOriginDestination`, the snapped endpoints are the first and last coordinates of the line, and the reported length is the network distance between them.

### 2.3 Requested origin and destination

With `setAppendOriginDestination(true)`, the caller's exact coordinates are prepended and appended. The straight legs from them to the snapped endpoints (or to the ports, see 2.4) are included in the length. Marseille to Cape Town measures 10,986.5 km node to node and 10,996.8 km with the endpoints appended.

### 2.4 Port

An entry in the embedded world ports database:

| Field | Meaning |
|---|---|
| `portCode()` | UN/LOCODE, for example `FRLEH`, `SGSIN`, `CNTSN` |
| `name()`, `country()` | Display name and country; 196 countries are represented |
| `isTerminal()` | True for 803 records flagged as container or cargo terminals |
| `toCountries()` | Permitted destination countries, populated for 747 ports; used by `countryRestricted` |
| `longitude()`, `latitude()` | Port position |

Port codes in the list follow the source dataset, not carrier convention, so check them before relying on them. The clearest case is Shanghai: the port and UN/LOCODE records disagree for `CNSHG`, so movement resolution uses UN/LOCODE's Shanghai Pt coordinate. The source also contains 38 duplicate-code groups. `getByCode` throws for an ambiguous code; inspect `getByCodeCandidates` or use `getByCode(code, near)` rather than relying on source order. A movement can supply its own coordinate for any code to override the list.

A port becomes a waypoint in two ways. Routing by port code uses the port position as the request point directly. Routing with `setIncludePorts(true)` replaces each request point with the nearest port that passes the filters in `PortParameters`: terminals only, country of loading, country of discharge, and strict or lenient matching. The chosen ports are reported in `port_origin` and `port_dest`.

Paris to Tokyo with `includePorts` and `onlyTerminals` produces this sequence: Paris (2.3) → Rouen `FRURO` (2.4) → snapped node (2.2) → a run of network nodes (2.1) → snapped node (2.2) → Tokyo `JPTYO` (2.4) → Tokyo city (2.3).

### 2.5 Preferred port in an area

The caller can supply polygons (`AreaFeature`) that name preferred ports with share weights. If a request point falls inside a polygon, the engine routes from every preferred port and returns one feature per port, with `share` normalised so the shares sum to one. Brussels inside a Belgium polygon with Antwerp at 250 and Le Havre at 200 yields two routes to Tokyo: `BEANR` at 56 % (20,977 km) and `FRLEH` at 44 % (20,630 km).

When a point is outside every polygon and `strictArea` is false, the nearest polygon within 2,000 km of the point is used instead.

### 2.6 Custom port

A `PortProps` entry whose code is not in the database becomes a synthetic port. Its position comes from finite, valid `x` and `y` values in the props dictionary. Missing or invalid coordinates throw an `IllegalArgumentException`; the engine never silently places a custom port at the request point.

### 2.7 Antimeridian seam node

Marnet is a flat map with a seam at the 180th meridian. The dataset stitches it with three internal edge tags that are never reported to callers:

| Tag | Undirected edges | Length | Purpose |
|---|---|---|---|
| `pacific_ocean` | 12 | 0 km | Zero-length links joining the −180° and +180° copies of the same node |
| `segment` | 13 | 16,680 km | Meridian lane running along +180° from 70° S to 80° N |
| `segment2` | 14 | 17,792 km | The same lane along −180° |

Some source nodes are duplicated east of the seam, out to 190.85° E. Route length is measured on the continuous internal path, then output is normalized to RFC 7946. A crossing is split exactly at ±180° and emitted as a `MultiLineString`; all output longitudes stay within ±180°.

### 2.8 Degenerate result

If both request points snap to the same node, the line is drawn straight between the two request points, so it has two coordinates and a real length. If passage restrictions leave no path, geometry is null and length and duration are zero; movement sea legs instead throw because silently omitting a leg would corrupt the totals.

## 3. Choke points

### 3.1 How they are modelled

A choke point is not a node. It is a tag on the edges that pass through a canal or strait. The tag is a lower-case identifier such as `suez`, exposed as constants on the `Passage` class.

- **Restriction.** Any passage named in `TradeRouterOptions.getRestrictions()` has its tagged edges removed from the search. The path then goes round, or fails if there is no alternative.
- **Reporting.** With `setReturnPassages(true)`, the output property `traversed_passages` lists the tags of every tagged edge the route used, deduplicated.
- **Default.** `northwest` is closed unless `TradeRouterOptions.setAllowNorthwest(true)` is called, and replacing the restrictions does not reopen it. Every other passage is open by default. `getClosedPassages()` returns the full set the search skips.

### 3.2 Catalogue

Locations are the bounding box of the tagged edges in the dataset. Detours were measured with the library; the default column uses the default restriction set and the closed column adds the named passage.

| Identifier | Constant | Connects | Tagged edges | Tagged length | Location (lon, lat) | Example pair | Default | Closed |
|---|---|---|---|---|---|---|---|---|
| `suez` | `Passage.SUEZ` | Mediterranean and Red Sea | 5 | 521 km | 32.3° to 34.5° E, 27.0° to 31.1° N | Singapore to Rotterdam | 15,525 km | 21,985 km via Sunda and the Cape |
| `panama` | `Passage.PANAMA` | Caribbean and Pacific | 10 | 285 km | 80.0° to 79.5° W, 7.4° to 9.8° N | Shanghai to New York | 19,779 km | 22,953 km via Malacca and Suez |
| `gibraltar` | `Passage.GIBRALTAR` | Atlantic and Mediterranean | 3 | 95 km | 5.8° to 4.7° W, 36.0° N | Marseille to New York | 7,220 km | 25,623 km via Suez and the Cape |
| `bosporus` | `Passage.BOSPORUS` | Black Sea and Sea of Marmara | 6 | 35 km | 29.0° to 29.1° E, 41.0° to 41.2° N | Odessa to Marseille | 3,149 km | No route |
| `dardanelles` | `Passage.DARDANELLES` | Sea of Marmara and Aegean | 5 | 257 km | 26.2° to 29.0° E, 40.1° to 41.0° N | Odessa to Marseille | 3,149 km | No route |
| `ormuz` | `Passage.ORMUZ` | Persian Gulf and Gulf of Oman | 4 | 153 km | 56.3° to 57.1° E, 25.5° to 26.5° N | Kuwait to Mumbai | 2,919 km | No route |
| `babalmandab` | `Passage.BABALMANDAB` | Red Sea and Gulf of Aden | 1 | 59 km | 43.3° to 43.8° E, 12.4° to 12.7° N | Jeddah to Mumbai | 4,420 km | 22,949 km via Suez, Gibraltar and the Cape |
| `malacca` | `Passage.MALACCA` | Indian Ocean and South China Sea | 1 | 135 km | 99.8° to 100.6° E, 3.2° to 4.1° N | Shanghai to Colombo | 7,061 km | 8,219 km via Sunda |
| `sunda` | `Passage.SUNDA` | Java Sea and Indian Ocean | 1 | 15 km | 105.8° to 105.9° E, 6.0° to 5.9° S | Shanghai to Colombo, Malacca also closed | 7,061 km | 9,352 km via an untagged strait further east |
| `chili` | `Passage.CHILI` | Atlantic and Pacific round South America | 5 | 857 km | 80.0° to 68.0° W, 60.0° to 52.4° S | Buenos Aires to Valparaíso | 5,611 km | 15,080 km via Panama |
| `south_africa` | `Passage.SOUTH_AFRICA` | Atlantic and Indian Ocean round Africa | 7 | 4,650 km | 18.0° to 30.0° E, 50.0° to 35.0° S | Singapore to Rotterdam, Suez also closed | 15,525 km | 22,787 km via Sunda and the far south |
| `bering` | `Passage.BERING` | Pacific and Arctic Ocean | 7 | 847 km | 169.5° W to 190.9° E, 62.1° to 66.0° N | Rotterdam to Yokohama, `allowNorthwest` | 14,055 km | 20,961 km via Suez |
| `northwest` | `Passage.NORTHWEST` | Atlantic and Pacific through the Arctic | 12 | 1,933 km | 171.7° W to 190.9° E, 66.0° to 74.3° N | Rotterdam to Yokohama | Closed unless `allowNorthwest` | Opening it gives 14,055 km |

### 3.3 Notes on each choke point

**Suez Canal (`suez`).** A sea-level canal of about 190 km between Port Said and Suez, with no locks. It is the shortest link between Europe and Asia; closing it in the model sends Singapore to Rotterdam traffic round the Cape of Good Hope, adding roughly 6,500 km, which at 16 knots is about nine extra days. The tagged edges also cover the Gulf of Suez approaches, which is why the tagged length exceeds the canal itself.

**Panama Canal (`panama`).** About 80 km of locks and lakes between the Caribbean and the Pacific. Vessel size is limited by the Neopanamax locks. Closing it in the model reroutes Shanghai to New York eastabout through Malacca and Suez rather than round Cape Horn, because that is shorter on this network.

**Strait of Gibraltar (`gibraltar`).** About 13 km wide at its narrowest, the only natural entrance to the Mediterranean. Closing it forces Atlantic traffic to reach the Mediterranean through Suez, which is why Marseille to New York grows from 7,220 km to 25,623 km. Suez and Gibraltar closed together make the Mediterranean unreachable from outside, which is the blocked-route case used in the test suite.

**Bosporus and Dardanelles (`bosporus`, `dardanelles`).** The two Turkish Straits, roughly 30 km and 60 km long, are the only exit from the Black Sea, and they are in series. Closing either one makes every Black Sea port unreachable; the model returns an empty route. Transit is governed by the Montreux Convention.

**Strait of Hormuz (`ormuz`).** About 40 km wide at its narrowest between Oman and Iran, and the only exit from the Persian Gulf. It carries a large share of the world's seaborne crude oil. Closing it isolates Kuwait, Iraq, Bahrain, Qatar, the UAE's Gulf coast and Saudi Arabia's Gulf ports; the model returns an empty route for any of them.

**Bab-el-Mandeb (`babalmandab`).** About 26 km wide between Yemen and Djibouti, joining the Red Sea to the Gulf of Aden. It is the southern gate of the Suez route, so a closure has the same continental effect as closing Suez for traffic beyond the Red Sea. Jeddah to Mumbai, normally 4,420 km, becomes a 22,949 km voyage out through Suez, Gibraltar and round the Cape.

**Strait of Malacca (`malacca`).** About 800 km long between Malaysia, Singapore and Sumatra, only a few kilometres wide at the Singapore end, and the busiest strait in the world. Draught is limited to around 25 m, the so-called Malaccamax. The model's tag covers the northern entrance near Penang. Closing it diverts Shanghai to Colombo through the Sunda Strait, adding about 1,150 km.

**Sunda Strait (`sunda`).** The passage between Java and Sumatra, around 24 km wide and shallow at its northern end. It is the first alternative when Malacca is closed. Closing both sends the model further east through an untagged strait such as Lombok, adding about 2,300 km to Shanghai to Colombo.

**Magellan Strait and Chilean channels (`chili`).** The sheltered route through the southern tip of South America, about 570 km long, avoiding Cape Horn. Closing it in the model sends Buenos Aires to Valparaíso traffic north through Panama, tripling the distance.

**Cape of Good Hope (`south_africa`).** Not a strait but the open-ocean turn round southern Africa. It matters as the fallback when Suez or Bab-el-Mandeb is closed. The tag covers about 4,650 km of lanes between 35° S and 50° S. Closing it as well as Suez still leaves a route, but a far southern one that adds a further 800 km.

**Bering Strait (`bering`).** About 82 km wide between Russia and Alaska, linking the Pacific to the Arctic Ocean. In the dataset it only matters in combination with `northwest`, because every Arctic route passes through both tags.

**Northwest Passage and Arctic lanes (`northwest`).** The tag covers the Arctic lanes north of 66° N between Alaska and the Canadian archipelago. These routes are seasonal and ice-dependent, so the library closes them unless `allowNorthwest` is true. Opening them cuts Rotterdam to Yokohama from 20,961 km to 14,055 km, a saving of about a third, which is the reason the option exists.

### 3.4 Combinations worth knowing

- **Mediterranean sealed.** `suez` and `gibraltar` together: no route between the Mediterranean and any other sea.
- **Black Sea sealed.** `bosporus` or `dardanelles` alone: no route to or from any Black Sea port.
- **Persian Gulf sealed.** `ormuz` alone: no route to or from any Gulf port.
- **Red Sea bypassed.** `suez` or `babalmandab`: Europe to Asia traffic goes round the Cape.
- **Indonesia bypassed.** `malacca` and `sunda`: traffic goes through an untagged strait further east, which cannot be restricted.
- **Arctic opened.** Call `setAllowNorthwest(true)`: Europe to north-east Asia traffic goes over the top of Russia.
- **Suez closed, Arctic still closed.** `setRestrictions(Passage.SUEZ)` alone keeps `northwest` shut, so Rotterdam to Yokohama goes west through Panama, 23,454 km port to port, rather than 14,051 km over the Arctic.

## 4. Reference

```java
import io.github.skuirrels.traderouter.TradeRouterOptions;
import io.github.skuirrels.traderouter.TradeRoutes;
import io.github.skuirrels.traderouter.passages.Passage;

var route = TradeRoutes.calculate(
    origin, destination,
    new TradeRouterOptions()
        .setRestrictions(Passage.SUEZ, Passage.BABALMANDAB) // Northwest stays closed
        .setReturnPassages(true)
        .setIncludePorts(true)
        .setAppendOriginDestination(true));

// route.properties().getTraversedPassages()          -> tags of choke points used
// route.properties().getPortOrigin() / getPortDest()  -> port waypoints chosen
// route.geometry().positions()                        -> requested point, port, snapped node, network nodes, ...
```

All identifiers, as constants and as strings:

| Constant | String |
|---|---|
| `Passage.BABALMANDAB` | `babalmandab` |
| `Passage.BERING` | `bering` |
| `Passage.BOSPORUS` | `bosporus` |
| `Passage.CHILI` | `chili` |
| `Passage.DARDANELLES` | `dardanelles` |
| `Passage.GIBRALTAR` | `gibraltar` |
| `Passage.MALACCA` | `malacca` |
| `Passage.NORTHWEST` | `northwest` |
| `Passage.ORMUZ` | `ormuz` |
| `Passage.PANAMA` | `panama` |
| `Passage.SOUTH_AFRICA` | `south_africa` |
| `Passage.SUEZ` | `suez` |
| `Passage.SUNDA` | `sunda` |

Strings are matched case-insensitively. Unknown strings in `Restrictions` throw an `IllegalArgumentException` instead of being silently ignored. Internal seam tags (`segment`, `segment2`, `pacific_ocean`) are never reported and cannot be supplied as public restrictions.
