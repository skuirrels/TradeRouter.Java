#!/usr/bin/env python3
"""List candidate aliases from official UN/LOCODE sea-port codes to port-list codes that UN/LOCODE lacks.

Reads the embedded ports.json.gz and unlocode.json.gz and prints JSON candidates for manual review. A candidate
pairs a port-list code absent from UN/LOCODE with a UN/LOCODE sea port in the same country that is not itself in
the port list, within --max-km, scored by name similarity. Nothing is written into the library; reviewed pairs
are copied by hand into traderouter/src/main/resources/io/github/skuirrels/traderouter/data/port-code-aliases.json.
"""
import argparse
import difflib
import gzip
import json
import math
import pathlib
import re
import unicodedata

DATA = pathlib.Path(__file__).resolve().parent.parent / "traderouter" / "src" / "main" / "resources" / "io" / "github" / "skuirrels" / "traderouter" / "data"
EXPANSIONS = {"st": "saint", "ste": "sainte", "pt": "port", "pto": "puerto", "mt": "mount", "is": "island"}
NOISE = {"harbour", "harbor", "terminal", "port", "puerto", "porto", "of", "de", "del", "la", "le", "the"}


def tokens(name):
    text = unicodedata.normalize("NFD", name)
    text = "".join(c for c in text if not unicodedata.combining(c)).lower()
    words = [EXPANSIONS.get(w, w) for w in re.findall(r"[a-z]+", text)]
    return [w for w in words if w not in NOISE] or words


def similarity(a, b):
    return difflib.SequenceMatcher(None, "".join(tokens(a)), "".join(tokens(b))).ratio()


def distance_km(lon1, lat1, lon2, lat2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(min(1.0, h)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--max-km", type=float, default=25.0)
    parser.add_argument("--min-similarity", type=float, default=0.0)
    args = parser.parse_args()

    ports = json.load(gzip.open(DATA / "ports.json.gz"))
    unlocodes = {row[0]: row for row in json.load(gzip.open(DATA / "unlocode.json.gz"))}
    port_codes = {p["port"] for p in ports}
    free_sea_ports = [row for row in unlocodes.values() if row[4] & 1 and row[2] is not None and row[0] not in port_codes]

    candidates = []
    for port in ports:
        if port["port"] in unlocodes:
            continue
        for row in free_sea_ports:
            if row[0][:2] != port["port"][:2]:
                continue
            km = distance_km(port["x"], port["y"], row[2], row[3])
            score = similarity(port["name"], row[1])
            if km <= args.max_km and score >= args.min_similarity:
                candidates.append({
                    "unlocode": row[0], "unlocode_name": row[1], "port_code": port["port"],
                    "port_name": port["name"], "km": round(km, 1), "name_similarity": round(score, 2)})

    candidates.sort(key=lambda c: (-c["name_similarity"], c["km"]))
    print(json.dumps(candidates, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
