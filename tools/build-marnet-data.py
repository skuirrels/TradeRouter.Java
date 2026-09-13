#!/usr/bin/env python3
"""Build the deterministic compact graph from searoute-py 1.6.0 Marnet and antimeridian segments."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path

EARTH_RADIUS_KM = 6371.0088
EXPECTED_HASHES = {
    "marnet": "111a82d949bb949c396a69a08828781314d3afe779c07e9754c3b2834dfca415",
    "segments": "56052b2ddd35de483ec00a29a7f0ffc37e162b0ee0249a9a435c8a8b2d8ffe0f",
}


def load(path: Path, kind: str) -> dict:
    content = path.read_bytes()
    actual = hashlib.sha256(content).hexdigest()
    if actual != EXPECTED_HASHES[kind]:
        raise ValueError(f"{kind} SHA-256 is {actual}, expected {EXPECTED_HASHES[kind]}")
    return json.loads(content)


def lines(feature: dict) -> list[list[list[float]]]:
    geometry = feature["geometry"]
    if geometry["type"] == "LineString":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiLineString":
        return geometry["coordinates"]
    raise ValueError(f"unsupported geometry {geometry['type']}")


def distance_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    lon1, lat1 = map(math.radians, a)
    lon2, lat2 = map(math.radians, b)
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    value = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return EARTH_RADIUS_KM * 2 * math.atan2(math.sqrt(value), math.sqrt(max(0, 1 - value)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("marnet", type=Path)
    parser.add_argument("segments", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    nodes: list[list[float]] = []
    node_ids: dict[tuple[float, float], int] = {}
    edges: list[list[object]] = []

    def node_id(position: list[float]) -> int:
        key = (position[0], position[1])
        if key not in node_ids:
            node_ids[key] = len(nodes)
            nodes.append([*key])
        return node_ids[key]

    for source in (load(args.segments, "segments"), load(args.marnet, "marnet")):
        for feature in source["features"]:
            passage = feature.get("properties", {}).get("passage")
            for line in lines(feature):
                for start, end in zip(line, line[1:]):
                    u = node_id(start)
                    v = node_id(end)
                    weight = round(distance_km(tuple(start), tuple(end)), 1)
                    edges.append([u, v, weight, passage])
                    edges.append([v, u, weight, passage])

    encoded = json.dumps({"nodes": nodes, "edges": edges}, separators=(",", ":")).encode()
    with args.output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as target:
            target.write(encoded)
    print(f"marnet: {len(nodes)} nodes, {len(edges)} directed edges; embedded SHA-256: {hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
