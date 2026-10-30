#!/usr/bin/env python3
"""Fill coordinates for UN/LOCODE airports that no embedded source can place.

An airport code (function 4 in UNECE's notation, bit 8 in the embedded list) qualifies when UNECE publishes no
coordinate for it and the coordinate supplement holds no other row for it. UN/LOCODE airport codes reuse the
airport's IATA code, so a code is placed at the OurAirports airport (public domain, pinned by SHA-256 in
tools/airport-coordinate-sources.json) that:

1. is the only open airport in OurAirports carrying the code's last three letters as its IATA code,
2. lies in the code's country, and
3. shares a distinctive word with the code's UN/LOCODE name, in its name, municipality or keywords, allowing
   for spelling variants: Arlanda in Stockholm-Arlanda Airport, Malpensa in Milan Malpensa International Airport.

The name check holds back codes whose IATA namesake is somewhere else, and also some genuine renames, such as
Ujung Pandang for Makassar; those stay on the review list.

Rows written by this script start their source with "Airport fill, ", so a rerun replaces them. Placed codes are
removed from the review list; a rerun does not restore them there.
"""

import argparse
import collections
import csv
import gzip
import hashlib
import json
import re
import unicodedata
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "traderouter/src/main/resources/io/github/skuirrels/traderouter/data"
MANIFEST = Path(__file__).with_name("airport-coordinate-sources.json")
MARKER = "Airport fill, "
AIRPORT = 8
GENERIC = {
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


def words(text):
    ascii_text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii").lower().replace("'", "")
    return [word for word in re.split(r"[^a-z]+", ascii_text) if len(word) >= 4 and word not in GENERIC]


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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", type=Path, help="folder holding the files named in airport-coordinate-sources.json")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text())
    for relative, expected in manifest["files"].items():
        if digest(args.inputs / relative) != expected:
            raise SystemExit(f"{relative} differs from the pinned {manifest['retrieved']} snapshot; review it before updating the manifest")

    with gzip.open(DATA / "unlocode.json.gz", "rt") as source:
        rows = json.load(source)
    with gzip.open(DATA / "unlocode-supplement.json.gz", "rt") as source:
        supplement = json.load(source)
    kept = [row for row in supplement if not row["source"].startswith(MARKER)]
    covered = {row["code"] for row in kept}
    missing = {row[0]: row for row in rows if row[2] is None and row[0] not in covered and row[4] & AIRPORT}

    airports = collections.defaultdict(list)
    with (args.inputs / "airports.csv").open(encoding="utf-8") as source:
        for airport in csv.DictReader(source):
            if airport["iata_code"] and airport["type"] != "closed":
                airports[(airport["iso_country"], airport["iata_code"])].append(airport)

    added, outcomes = [], collections.Counter()
    for code in sorted(missing):
        matches = airports.get((code[:2], code[2:]), [])
        if len(matches) != 1:
            outcomes["no unique IATA match" if matches else "no IATA match"] += 1
            continue
        airport = matches[0]
        theirs = words(" ".join((airport["name"], airport["municipality"], airport["keywords"])))
        if not any(same_word(ours, other) for ours in words(missing[code][1]) for other in theirs):
            outcomes["name disagrees"] += 1
            continue
        outcomes["placed"] += 1
        place = f", {airport['municipality']}" if airport["municipality"] else ""
        added.append({"code": code, "name": missing[code][1],
                      "lon": round(float(airport["longitude_deg"]), 5), "lat": round(float(airport["latitude_deg"]), 5),
                      "source": f"{MARKER}OurAirports {airport['ident']} {airport['name']}{place}, IATA {airport['iata_code']}"})

    combined = [row for row in kept if not row["source"].startswith("GeoNames (geonameId ")] + added
    combined += [row for row in kept if row["source"].startswith("GeoNames (geonameId ")]
    lines = ["  " + json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in combined]
    encoded = ("[\n" + ",\n".join(lines) + "\n]\n").encode()
    output = DATA / "unlocode-supplement.json.gz"
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as target:
            target.write(encoded)

    review_path = ROOT / "docs/unlocode-unresolved.csv"
    placed = {row["code"] for row in added}
    with review_path.open(newline="") as source:
        review = list(csv.reader(source))
    with review_path.open("w", newline="") as target:
        csv.writer(target, lineterminator="\n").writerows([review[0]] + [row for row in review[1:] if row[0] not in placed])

    print(json.dumps({
        "candidates": len(missing), "outcomes": dict(outcomes),
        "supplement_sha256": digest(output), "supplement_json_sha256": hashlib.sha256(encoded).hexdigest(),
        "review_list_sha256": digest(review_path),
    }, indent=2))


if __name__ == "__main__":
    main()
