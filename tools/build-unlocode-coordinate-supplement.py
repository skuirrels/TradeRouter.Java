#!/usr/bin/env python3
"""Update the single attributed coordinate supplement from GeoNames links.

Download alternateNamesV2.zip and allCountries.zip from
https://download.geonames.org/export/dump/ and pass their local paths. Only
unambiguous, same-country, same-name mappings fill missing embedded positions.
Existing entries from other sources are kept; generated GeoNames entries are refreshed.
"""

import argparse
import collections
import csv
import gzip
import hashlib
import json
import re
import unicodedata
import zipfile
from pathlib import Path


DATA = Path(__file__).resolve().parents[1] / "traderouter/src/main/resources/io/github/skuirrels/traderouter/data"
SOURCE_HASHES = {
    "alternateNamesV2.zip": "745105840324aee7efea4dd50964b85c8266bddbc53f56a4950d9dcdaf2e4c50",
    "allCountries.zip": "de79dd4dcfc2e6303e0121c0e3440426989ce38d23cf88ab5abc7d9d9bc4cf40",
}


def normalized(name):
    ascii_name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]", "", ascii_name.lower())


def name_matches(name, feature):
    expected = normalized(name)
    return expected in {normalized(value) for value in (feature[1], feature[2], *feature[3].split(","))}


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("alternate_names", type=Path)
    parser.add_argument("all_countries", type=Path)
    parser.add_argument("--output", type=Path, default=DATA / "unlocode-supplement.json.gz")
    parser.add_argument("--unresolved", type=Path, default=DATA.parents[8] / "docs/unlocode-unresolved.csv")
    args = parser.parse_args()
    hashes = {"alternateNamesV2.zip": digest(args.alternate_names),
              "allCountries.zip": digest(args.all_countries)}
    if hashes != SOURCE_HASHES:
        parser.error("The GeoNames inputs differ from the documented 2026-09-16 snapshot; review them before updating SOURCE_HASHES")

    with gzip.open(DATA / "unlocode.json.gz", "rt") as source:
        rows = json.load(source)
    missing = {row[0]: row for row in rows if row[2] is None}
    with gzip.open(DATA / "unlocode-supplement.json.gz", "rt") as source:
        existing = json.load(source)
    retained = [row for row in existing if not row["source"].startswith("GeoNames (geonameId ")]
    manual = {row["code"] for row in retained}

    linked = collections.defaultdict(set)
    with zipfile.ZipFile(args.alternate_names) as archive:
        with archive.open("alternateNamesV2.txt") as source:
            for line in source:
                fields = line.decode("utf-8").split("\t")
                if len(fields) > 3 and fields[2] == "unlc" and fields[3] in missing:
                    linked[fields[3]].add(fields[1])

    ids = {id for group in linked.values() for id in group}
    features = {}
    with zipfile.ZipFile(args.all_countries) as archive:
        with archive.open("allCountries.txt") as source:
            for line in source:
                fields = line.decode("utf-8").split("\t")
                if fields[0] in ids:
                    features[fields[0]] = fields

    accepted = []
    unresolved = []
    rejected = collections.Counter()
    examples = collections.defaultdict(list)
    for code, row in missing.items():
        if code in manual:
            continue
        if code not in linked:
            rejected["no explicit link"] += 1
            unresolved.append((code, row[1], "no explicit link", ""))
            continue
        candidates = [features[id] for id in linked[code] if id in features]
        candidates = [x for x in candidates if x[8] == code[:2] and name_matches(row[1], x)]
        positions = {(float(x[5]), float(x[4])) for x in candidates}
        if len(positions) != 1:
            reason = "conflicting or missing match"
            rejected[reason] += 1
            unresolved.append((code, row[1], reason, ";".join(sorted(linked[code], key=int))))
            if len(examples[reason]) < 8:
                examples[reason].append((code, row[1], [(x[0], x[1], x[4], x[5]) for x in candidates]))
            continue
        lon, lat = positions.pop()
        if not (-180 <= lon <= 180 and -90 <= lat <= 90):
            rejected["invalid position"] += 1
            unresolved.append((code, row[1], "invalid position", ";".join(sorted(linked[code], key=int))))
            continue
        feature = min(candidates, key=lambda x: int(x[0]))
        accepted.append({"code": code, "name": row[1], "lon": lon, "lat": lat,
                         "source": f"GeoNames (geonameId {feature[0]})"})

    accepted.sort(key=lambda row: row["code"])
    combined = retained + accepted
    lines = ["  " + json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in combined]
    encoded = ("[\n" + ",\n".join(lines) + "\n]\n").encode()
    with args.output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as target:
            target.write(encoded)
    with args.unresolved.open("w", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(("code", "name", "reason", "geoname_ids"))
        writer.writerows(unresolved)
    print(json.dumps({
        "source_hashes": hashes,
        "missing_before": len(missing), "explicit_links": len(linked),
        "accepted": len(accepted), "rejected": rejected, "examples": examples,
        "output_sha256": digest(args.output),
        "unresolved_sha256": digest(args.unresolved),
    }, indent=2))


if __name__ == "__main__":
    main()
