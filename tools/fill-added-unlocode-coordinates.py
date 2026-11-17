#!/usr/bin/env python3
"""Fill coordinates, add-only, for UN/LOCODEs that no embedded source places.

A code qualifies when the embedded list has no coordinate for it and the coordinate supplement holds no row for
it: the codes on docs/unlocode-unresolved.csv. Existing supplement rows are never changed. Two kinds of code are
left alone: codes the port list or the port-code aliases use, because a UN/LOCODE position changes how they
resolve, and sea-port and airport codes of the original import, which tools/fill-sea-port-coordinates.py and
tools/fill-airport-coordinates.py already reviewed, leaving some unplaced on purpose as namesakes. Rows written by this
script start their source with "Added fill, ", so a rerun replaces only them.

Pass the folder holding the files pinned in tools/added-unlocode-coordinate-sources.json. A code is placed by
the first rule that gives exactly one position:

1. GeoNames link: GeoNames features linked to the code by an alternate name of type "unlc", in the code's
   country, whose name or alternate names match the UN/LOCODE name, at exactly one position. This is the rule
   of tools/build-unlocode-coordinate-supplement.py, applied to a newer GeoNames snapshot.
2. Airport: for an airport code, the only open OurAirports airport in the country carrying the code's last three
   letters as IATA code and sharing a distinctive name word, as in tools/fill-airport-coordinates.py.
3. Wikidata: Wikidata items carrying the code as UN/LOCODE (P1937), at one position (within 1 km), whose label
   matches the UN/LOCODE name and which a same-named GeoNames populated place in the country confirms within
   15 km.
4. GeoNames name: GeoNames populated places in the country whose name, ASCII name or alternate names match the
   UN/LOCODE name, all within 10 km of each other; the most populous is used.

The UN/LOCODE name is matched without any text in parentheses and without text after a slash or comma, which
names a city or region the location serves. Every placed position must also pass two screens. The first is the one
used for UNECE coordinates: no more than 1,500 km and five times the country's 90th-percentile distance from its
median position. The second compares the position with the other placed codes in the code's UNECE 2025-1
subdivision, where there are at least five: it must lie within 50 km, or twice the subdivision's 90th-percentile
distance, of their median. Rule 4 also needs that subdivision check to confirm it. Placed codes leave the review
list.
"""

import argparse
import collections
import csv
import gzip
import hashlib
import io
import json
import math
import re
import statistics
import unicodedata
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "traderouter/src/main/resources/io/github/skuirrels/traderouter/data"
MANIFEST = Path(__file__).with_name("added-unlocode-coordinate-sources.json")
MARKER = "Added fill, "
AIRPORT = 8
AIRPORT_GENERIC = {
    "apt", "airport", "int", "intl", "international", "regional", "municipal", "county", "field", "airfield",
    "aerodrome", "aeropuerto", "aeroporto", "aeroport", "flughafen", "lufthavn", "metropolitan", "city", "the",
    "new", "old", "saint", "sainte", "santa", "port", "fort", "lake", "island", "river", "north", "south", "east",
    "west",
}


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def normalized(name):
    ascii_name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]", "", ascii_name.lower())


def main_name(name):
    """'Pemangkat, Kl' -> 'Pemangkat'; 'Helsingfors (Helsinki)' -> 'Helsingfors'."""
    name = re.split(r"[/,]", name)[0]
    return re.sub(r"\([^)]*\)", "", name).strip()


def words(text):
    ascii_text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii").lower().replace("'", "")
    return [word for word in re.split(r"[^a-z]+", ascii_text) if len(word) >= 4 and word not in AIRPORT_GENERIC]


def edit_distance(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def same_word(a, b):
    if a == b:
        return True
    shorter = min(len(a), len(b))
    return shorter >= 5 and (a.startswith(b) or b.startswith(a) or edit_distance(a, b) <= max(1, shorter // 5))


def haversine_km(a, b):
    lon1, lat1, lon2, lat2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(h))


def feature_names(fields):
    return {normalized(value) for value in (fields[1], fields[2], *fields[3].split(",")) if value}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", type=Path, help="folder holding the files named in added-unlocode-coordinate-sources.json")
    parser.add_argument("--report", type=Path, help="write every placement and its evidence as CSV")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text())
    for relative, expected in manifest["files"].items():
        if digest(args.inputs / relative) != expected:
            raise SystemExit(f"{relative} differs from the pinned {manifest['retrieved']} snapshot; review it before updating the manifest")

    with gzip.open(DATA / "unlocode.json.gz", "rt", encoding="utf-8") as source:
        rows = json.load(source)
    with gzip.open(DATA / "unlocode-supplement.json.gz", "rt", encoding="utf-8") as source:
        supplement = json.load(source)
    kept = [row for row in supplement if not row["source"].startswith(MARKER)]
    covered = {row["code"] for row in kept}
    with gzip.open(DATA / "ports.json.gz", "rt", encoding="utf-8") as source:
        port_codes = {item["port"] for item in json.load(source)}
    with (DATA / "port-code-aliases.json").open(encoding="utf-8") as source:
        for item in json.load(source):
            port_codes.update((item["code"], item["port"]))
    with (ROOT / "docs/unlocode-unresolved.csv").open(newline="", encoding="utf-8") as source:
        reasons = {item["code"]: item["reason"] for item in csv.DictReader(source)}
    original_review = {"no explicit link", "conflicting or missing match"}
    missing = {row[0]: row for row in rows if row[2] is None and row[0] not in covered and row[0] not in port_codes
               and not (row[4] & (1 | AIRPORT) and reasons.get(row[0]) in original_review)}
    keys = {code: normalized(main_name(row[1])) for code, row in missing.items()}
    countries = {code[:2] for code in missing}
    wanted_names = collections.defaultdict(set)
    for code, key in keys.items():
        if len(key) >= 3:
            wanted_names[code[:2]].add(key)

    linked = collections.defaultdict(set)
    with zipfile.ZipFile(args.inputs / "alternateNamesV2.zip") as archive:
        with archive.open("alternateNamesV2.txt") as source:
            for line in io.TextIOWrapper(source, encoding="utf-8"):
                fields = line.split("\t")
                if len(fields) > 3 and fields[2] == "unlc" and fields[3].strip() in missing:
                    linked[fields[3].strip()].add(fields[1])
    linked_ids = {id for group in linked.values() for id in group}

    features = {}
    places = collections.defaultdict(list)  # (country, name key) -> populated places
    with zipfile.ZipFile(args.inputs / "allCountries.zip") as archive:
        with archive.open("allCountries.txt") as source:
            for line in io.TextIOWrapper(source, encoding="utf-8"):
                fields = line.rstrip("\n").split("\t")
                if fields[0] in linked_ids:
                    features[fields[0]] = fields
                if fields[6] == "P" and fields[8] in countries:
                    for key in feature_names(fields) & wanted_names[fields[8]]:
                        places[(fields[8], key)].append(fields)

    airports = collections.defaultdict(list)
    with (args.inputs / "airports.csv").open(encoding="utf-8") as source:
        for airport in csv.DictReader(source):
            if airport["iata_code"] and airport["type"] != "closed":
                airports[(airport["iso_country"], airport["iata_code"])].append(airport)

    wikidata = collections.defaultdict(list)
    with (args.inputs / "wikidata-unlocode-coords.csv").open(encoding="utf-8-sig", newline="") as source:
        for item in csv.DictReader(source):
            m = re.fullmatch(r"Point\(([-0-9.eE]+) ([-0-9.eE]+)\)", item.get("coord") or "")
            code = (item.get("code") or "").replace(" ", "").upper()
            if m and code in missing:
                wikidata[code].append((item["item"].rsplit("/", 1)[-1], item["itemLabel"], float(m[1]), float(m[2])))

    def place_by_link(code, row):
        candidates = [features[id] for id in linked.get(code, ()) if id in features]
        candidates = [x for x in candidates if x[8] == code[:2] and normalized(row[1]) in feature_names(x) | {keys[code]}
                      or x[8] == code[:2] and keys[code] in feature_names(x)]
        positions = {(float(x[5]), float(x[4])) for x in candidates}
        if len(positions) != 1:
            return None
        feature = min(candidates, key=lambda x: int(x[0]))
        lon, lat = positions.pop()
        return lon, lat, f"GeoNames {feature[1]}, geonameId {feature[0]}, linked by UN/LOCODE"

    def place_by_airport(code, row):
        if not row[4] & AIRPORT:
            return None
        matches = airports.get((code[:2], code[2:]), [])
        if len(matches) != 1:
            return None
        airport = matches[0]
        theirs = words(" ".join((airport["name"], airport["municipality"], airport["keywords"])))
        if not any(same_word(ours, other) for ours in words(row[1]) for other in theirs):
            return None
        place = f", {airport['municipality']}" if airport["municipality"] else ""
        return (float(airport["longitude_deg"]), float(airport["latitude_deg"]),
                f"OurAirports {airport['ident']} {airport['name']}{place}, IATA {airport['iata_code']}")

    def place_by_wikidata(code, row):
        items = [x for x in wikidata.get(code, ()) if normalized(main_name(x[1])) == keys[code] or normalized(x[1]) == keys[code]]
        if not items or any(haversine_km(items[0][2:], x[2:]) > 1 for x in items):
            return None
        item = min(items, key=lambda x: int(x[0][1:]))
        nearby = [p for p in places.get((code[:2], keys[code]), ()) if haversine_km(item[2:], (float(p[5]), float(p[4]))) <= 15]
        if not nearby:
            return None
        confirm = min(nearby, key=lambda p: haversine_km(item[2:], (float(p[5]), float(p[4]))))
        km = haversine_km(item[2:], (float(confirm[5]), float(confirm[4])))
        return item[2], item[3], f"Wikidata {item[0]} {item[1]}, linked by UN/LOCODE; GeoNames {confirm[1]}, geonameId {confirm[0]}, {km:.1f} km away"

    def place_by_name(code, row):
        if len(keys[code]) < 4:
            return None
        candidates = places.get((code[:2], keys[code]), [])
        if not candidates:
            return None
        positions = [(float(x[5]), float(x[4])) for x in candidates]
        if any(haversine_km(a, b) > 10 for a in positions for b in positions):
            return None
        best = max(candidates, key=lambda x: (int(x[14] or 0), -int(x[0])))
        others = len(candidates) - 1
        also = f"; {others} more within 10 km" if others else ""
        return (float(best[5]), float(best[4]),
                f"GeoNames {best[1]}, geonameId {best[0]}, the only populated place of that name in the country{also}")

    rules = (("GeoNames link", place_by_link), ("Airport", place_by_airport),
             ("Wikidata", place_by_wikidata), ("GeoNames name", place_by_name))
    placed, outcomes = {}, collections.Counter()
    for code in sorted(missing):
        for label, rule in rules:
            found = rule(code, missing[code])
            if found:
                lon, lat, evidence = found
                if -180 <= lon <= 180 and -90 <= lat <= 90:
                    placed[code] = (label, round(lon, 5), round(lat, 5), evidence)
                    break
        else:
            outcomes["not placed"] += 1

    # Screen every placement against its country's spread, as tools/merge-unlocode-release.py does for UNECE
    # coordinates, and against the spread of the other placed codes in its UNECE subdivision.
    subdivision = {}
    with zipfile.ZipFile(args.inputs / "unece-2025-1.zip") as archive:
        for n in (1, 2, 3):
            with archive.open(f"release/csv/UNLOCODE CodeListPart{n}.csv") as raw:
                for item in csv.reader(io.TextIOWrapper(raw, encoding="utf-8")):
                    if len(item) >= 11 and item[1] and item[2] and item[5].strip():
                        subdivision[item[1] + item[2]] = item[5].strip()
    supplement_positions = {row["code"]: (row["lon"], row["lat"]) for row in kept}
    by_country, by_subdivision = collections.defaultdict(list), collections.defaultdict(list)
    for row in rows:
        position = supplement_positions.get(row[0]) or ((row[2], row[3]) if row[2] is not None else None)
        if position:
            by_country[row[0][:2]].append(position)
            if row[0] in subdivision:
                by_subdivision[(row[0][:2], subdivision[row[0]])].append(position)

    def spread(points):
        median = (statistics.median(p[0] for p in points), statistics.median(p[1] for p in points))
        distances = sorted(haversine_km(median, p) for p in points)
        return median, distances[int(0.9 * (len(distances) - 1))]

    country_spread = {key: spread(points) for key, points in by_country.items() if len(points) >= 10}
    subdivision_spread = {key: spread(points) for key, points in by_subdivision.items() if len(points) >= 5}
    screened = []
    for code, (label, lon, lat, evidence) in sorted(placed.items()):
        if code[:2] in country_spread:
            median, p90 = country_spread[code[:2]]
            distance = haversine_km(median, (lon, lat))
            if distance > 1500 and distance > 5 * p90:
                screened.append((code, "outside its country", round(distance)))
                continue
        key = (code[:2], subdivision.get(code, ""))
        if key in subdivision_spread:
            median, p90 = subdivision_spread[key]
            distance = haversine_km(median, (lon, lat))
            if distance > max(50, 2 * p90):
                screened.append((code, f"outside its subdivision {key[1]}", round(distance)))
                continue
            placed[code] = (label, lon, lat, f"{evidence}; in its UN/LOCODE subdivision {key[1]}")
        elif label == "GeoNames name":
            # A name alone is weak evidence: it needs the subdivision to confirm it.
            screened.append((code, "no subdivision to confirm the name", 0))
            continue
        outcomes[label] += 1
    for code, *_ in screened:
        del placed[code]
    outcomes["held back"] = collections.Counter(reason.split(" ")[0] + " " + " ".join(reason.split(" ")[1:3]) for _, reason, _ in screened)

    added = [{"code": code, "name": missing[code][1], "lon": lon, "lat": lat, "source": f"{MARKER}{evidence}"}
             for code, (label, lon, lat, evidence) in sorted(placed.items())]
    generated = [row for row in kept if row["source"].startswith("GeoNames (geonameId ")]
    combined = [row for row in kept if not row["source"].startswith("GeoNames (geonameId ")] + added + generated
    lines = ["  " + json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in combined]
    encoded = ("[\n" + ",\n".join(lines) + "\n]\n").encode()
    output = DATA / "unlocode-supplement.json.gz"
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as target:
            target.write(encoded)

    review_path = ROOT / "docs/unlocode-unresolved.csv"
    with review_path.open(newline="", encoding="utf-8") as source:
        review = list(csv.reader(source))
    with review_path.open("w", newline="", encoding="utf-8") as target:
        csv.writer(target, lineterminator="\n").writerows([review[0]] + [row for row in review[1:] if row[0] not in placed])

    if args.report:
        with args.report.open("w", newline="", encoding="utf-8") as target:
            writer = csv.writer(target)
            writer.writerow(("code", "name", "rule", "lon", "lat", "evidence"))
            for code, (label, lon, lat, evidence) in sorted(placed.items()):
                writer.writerow((code, missing[code][1], label, lon, lat, evidence))
    print(json.dumps({
        "candidates": len(missing), "outcomes": dict(outcomes),
        "held_back": screened[:20],
        "supplement_sha256": digest(output), "supplement_json_sha256": hashlib.sha256(encoded).hexdigest(),
        "review_list_sha256": digest(review_path),
    }, indent=2))


if __name__ == "__main__":
    main()
