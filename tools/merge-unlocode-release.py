#!/usr/bin/env python3
"""Add codes and missing coordinates from a UNECE UN/LOCODE release to the embedded list, changing nothing else.

Pass the path of the UNECE release zip, as downloaded from https://unlocode.unece.org/publications/.
The merge is add-only:

- Every embedded row keeps its code, name, coordinate, functions and position in the file.
- An embedded row without a coordinate takes the release's coordinate, unless the coordinate
  supplement already places the code (the supplement wins at load time, so filling it would change
  nothing and would break the rule that supplement fill rows exist only for codes UNECE leaves empty).
- A code the embedded list lacks is added, unless the release marks it for deletion ("X").
- A code the port list or the port-code aliases use is left exactly as it is, neither added nor filled,
  because a UN/LOCODE row or position changes how such a code resolves: the port list's position is
  tuned to the lane network, and a UN/LOCODE row under a port-list code can name a different place
  (the port list's NOKRN is Kristiansund, UNECE 2025-1 adds NOKRN as Kroderen, 300 km inland).
- A coordinate taken from the release is held back when it lies more than 1,500 km from its
  country's median position and more than five times the country's 90th-percentile distance,
  the screen that found the UNECE errors corrected in the supplement. The code is still added.

The release is pinned by SHA-256, and the tool is deterministic: a rerun on its own output adds nothing.
The review list, docs/unlocode-unresolved.csv, loses the codes that gain a coordinate and gains the
added codes that have none.
"""

import argparse
import csv
import gzip
import hashlib
import io
import json
import math
import re
import statistics
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "traderouter/src/main/resources/io/github/skuirrels/traderouter/data"
RELEASES = {
    # UNECE 2025-1 production archive, CSV parts dated 8 May 2026 inside the zip.
    "ad409fc7149b10f98d61190c34d9daf78b78bb8b31464cc66de1a89d09b01b5d": "UNECE 2025-1",
}
PARTS = [f"release/csv/UNLOCODE CodeListPart{n}.csv" for n in (1, 2, 3)]


def parse_coordinate(text):
    """'3327S 01844E' -> (18.73333, -33.45); None when absent or malformed."""
    m = re.fullmatch(r"(\d{2})(\d{2})([NS]) (\d{3})(\d{2})([EW])", text.strip())
    if not m:
        return None
    lat = int(m[1]) + int(m[2]) / 60
    lon = int(m[4]) + int(m[5]) / 60
    if int(m[2]) >= 60 or int(m[5]) >= 60 or lat > 90 or lon > 180:
        return None
    return round(-lon if m[6] == "W" else lon, 5), round(-lat if m[3] == "S" else lat, 5)


def parse_functions(text):
    """'-2345---' -> rail | road | airport | postal; position i sets bit i, '0' means not known."""
    value = 0
    for i, ch in enumerate(text[:8]):
        if ch not in "-0 ":
            value |= 1 << i
    return value


def sort_key(code, name):
    return code[:2], re.sub(r"[^0-9a-z]", "", name.lower()), code


def haversine_km(a, b):
    lon1, lat1, lon2, lat2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(h))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("release_zip", type=Path)
    args = parser.parse_args()

    digest = hashlib.sha256(args.release_zip.read_bytes()).hexdigest()
    if digest not in RELEASES:
        parser.error(f"{args.release_zip} (SHA-256 {digest}) is not a reviewed release; add it to RELEASES after review")
    edition = RELEASES[digest]

    release = {}
    with zipfile.ZipFile(args.release_zip) as archive:
        for part in PARTS:
            with archive.open(part) as raw:
                for row in csv.reader(io.TextIOWrapper(raw, encoding="utf-8")):
                    if len(row) >= 11 and row[1] and row[2]:
                        release[row[1] + row[2]] = row

    unlocode_path = DATA / "unlocode.json.gz"
    with gzip.open(unlocode_path, "rt", encoding="utf-8") as source:
        rows = json.load(source)
    with gzip.open(DATA / "unlocode-supplement.json.gz", "rt", encoding="utf-8") as source:
        supplement = {item["code"]: (item["lon"], item["lat"]) for item in json.load(source)}

    with gzip.open(DATA / "ports.json.gz", "rt", encoding="utf-8") as source:
        port_codes = {item["port"] for item in json.load(source)}
    with (DATA / "port-code-aliases.json").open(encoding="utf-8") as source:
        for item in json.load(source):
            port_codes.update((item["code"], item["port"]))

    embedded = {row[0] for row in rows}
    port_list_skips = []
    filled, skipped_supplement, added = [], [], []
    for row in rows:
        release_row = release.get(row[0])
        if row[2] is None and release_row:
            coordinate = parse_coordinate(release_row[10])
            if coordinate is None:
                continue
            if row[0] in supplement:
                skipped_supplement.append(row[0])
                continue
            if row[0] in port_codes:
                port_list_skips.append((row[0], row[1], coordinate))
                continue
            row[2], row[3] = coordinate
            filled.append(row)

    deleted = 0
    for code in sorted(release):
        if code in embedded:
            continue
        release_row = release[code]
        if release_row[0] == "X":
            deleted += 1
            continue
        coordinate = parse_coordinate(release_row[10])
        if code in port_codes:
            port_list_skips.append((code, release_row[4], coordinate))
            continue
        added.append([code, release_row[4], *(coordinate or (None, None)), parse_functions(release_row[6])])

    # Screen every coordinate this run takes from the release against its country's spread.
    effective = {}
    for row in rows + added:
        position = supplement.get(row[0]) or ((row[2], row[3]) if row[2] is not None else None)
        if position:
            effective.setdefault(row[0][:2], []).append(position)
    spread = {}
    for country, positions in effective.items():
        median = (statistics.median(p[0] for p in positions), statistics.median(p[1] for p in positions))
        distances = sorted(haversine_km(median, p) for p in positions)
        spread[country] = (median, distances[min(len(distances) - 1, int(0.9 * len(distances)))])
    held = []
    for row in filled + added:
        if row[2] is None:
            continue
        median, p90 = spread[row[0][:2]]
        distance = haversine_km(median, (row[2], row[3]))
        if distance > 1500 and distance > 5 * p90:
            held.append((row[0], row[1], row[2], row[3], round(distance)))
            row[2] = row[3] = None
    held_codes = {code for code, *_ in held}
    filled = [row for row in filled if row[0] not in held_codes]

    # Insert added rows at their sorted place without moving any embedded row.
    added.sort(key=lambda row: sort_key(row[0], row[1]))
    merged, i = [], 0
    for row in rows:
        while i < len(added) and sort_key(added[i][0], added[i][1]) < sort_key(row[0], row[1]) and added[i][0][:2] == row[0][:2]:
            merged.append(added[i])
            i += 1
        while i < len(added) and added[i][0][:2] < row[0][:2]:
            merged.append(added[i])
            i += 1
        merged.append(row)
    merged.extend(added[i:])
    assert len(merged) == len(rows) + len(added)

    text = json.dumps(merged, ensure_ascii=False, separators=(",", ":"))
    with open(unlocode_path, "wb") as target:
        with gzip.GzipFile(filename="unlocode.json", mode="wb", fileobj=target, compresslevel=9, mtime=0) as archive:
            archive.write(text.encode("utf-8"))

    review_path = ROOT / "docs/unlocode-unresolved.csv"
    with review_path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        fields, review = reader.fieldnames, list(reader)
    placed = {row[0] for row in filled}
    review = [item for item in review if item["code"] not in placed]
    listed = {item["code"] for item in review}
    for row in added:
        if row[2] is None and row[0] not in listed and row[0] not in supplement:
            reason = f"{edition} coordinate held back, outside its country" if row[0] in held_codes else f"added from {edition} without a coordinate"
            review.append({"code": row[0], "name": row[1], "reason": reason, "geoname_ids": ""})
    for code, name, *_ in held:
        if code not in placed and code in embedded and code not in listed:
            review.append({"code": code, "name": name, "reason": f"{edition} coordinate held back, outside its country", "geoname_ids": ""})
    order = {row[0]: i for i, row in enumerate(merged)}
    review.sort(key=lambda item: order.get(item["code"], len(order)))
    with review_path.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(review)

    print(f"{edition}: {len(release)} codes; embedded {len(rows)} -> {len(merged)}")
    print(f"added {len(added)} codes, {sum(1 for row in added if row[2] is not None)} with a coordinate; skipped {deleted} marked for deletion")
    print(f"filled {len(filled)} missing coordinates; left {len(skipped_supplement)} to the supplement")
    print(f"left {len(port_list_skips)} codes the port list uses unchanged:")
    for code, name, coordinate in sorted(port_list_skips):
        print(f"  {code} {name}: {'embedded, coordinate not filled' if code in embedded else 'not added'}, release {coordinate}")
    print(f"held back {len(held)} coordinates outside their country:")
    for code, name, lon, lat, distance in held:
        print(f"  {code} {name}: {lon}, {lat}, {distance} km from the country median")
    print(f"review list: {len(review)} codes")
    print(f"unlocode.json.gz SHA-256 {hashlib.sha256(unlocode_path.read_bytes()).hexdigest()}, JSON {hashlib.sha256(text.encode('utf-8')).hexdigest()}")
    print(f"unlocode-unresolved.csv SHA-256 {hashlib.sha256(review_path.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
