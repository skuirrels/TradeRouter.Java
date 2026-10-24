#!/usr/bin/env python3
"""Extract the selected Natural Earth 1:50m marine polygons into the deterministic embedded sea-area dataset."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path

EXPECTED_SOURCE_HASH = "6fe58083e0cc5c7fad9e396970e28a8580bbd8770cfa4d1d7b5a34423e912f97"


def polygons(geometry: dict) -> list:
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiPolygon":
        return geometry["coordinates"]
    raise ValueError(f"unsupported geometry {geometry['type']}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="ne_50m_geography_marine_polys.geojson")
    parser.add_argument("selection", type=Path, help="traderouter/src/main/resources/io/github/skuirrels/traderouter/data/sea-areas.json")
    parser.add_argument("output", type=Path, help="traderouter/src/main/resources/io/github/skuirrels/traderouter/data/sea-area-polygons.json.gz")
    args = parser.parse_args()

    content = args.source.read_bytes()
    actual = hashlib.sha256(content).hexdigest()
    if actual != EXPECTED_SOURCE_HASH:
        raise ValueError(f"source SHA-256 is {actual}, expected {EXPECTED_SOURCE_HASH}")

    features: dict[int, list[dict]] = {}
    for feature in json.loads(content)["features"]:
        features.setdefault(int(feature["properties"]["ne_id"]), []).append(feature)

    selection = json.loads(args.selection.read_text(encoding="utf-8"))
    areas = []
    vertices = 0
    for area in selection:
        matches = features.get(area["ne_id"], [])
        if len(matches) != 1:
            raise ValueError(f"{area['id']}: expected one feature with ne_id {area['ne_id']}, found {len(matches)}")
        properties = matches[0]["properties"]
        # Names and classes compare without case: the source spells INDIAN OCEAN and SOUTHERN OCEAN in capitals.
        checks = {"name": (area["name"], str.casefold), "featurecla": (area["class"], str.casefold), "wikidataid": (area["wikidata"], str)}
        for key, (value, normalise) in checks.items():
            source_value = properties.get(key) or ""
            if normalise(source_value) != normalise(value):
                raise ValueError(f"{area['id']}: source {key} is {source_value!r}, selection has {value!r}")
        parts = polygons(matches[0]["geometry"])
        vertices += sum(len(ring) for part in parts for ring in part)
        areas.append({"ne_id": area["ne_id"], "polygons": parts})

    encoded = json.dumps({"areas": areas}, separators=(",", ":")).encode()
    with args.output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as target:
            target.write(encoded)
    print(f"sea areas: {len(areas)} areas, {vertices} vertices; embedded SHA-256: {hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
