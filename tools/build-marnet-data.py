#!/usr/bin/env python3
"""Build the deterministic compact graph from searoute-py 1.6.0 Marnet and antimeridian segments, with the Kiel Canal
from Eurostat SeaRoute."""

from __future__ import annotations

import argparse
import contextlib
import gzip
import hashlib
import json
import math
import sqlite3
import struct
from pathlib import Path

EARTH_RADIUS_KM = 6371.0088
EXPECTED_HASHES = {
    "marnet": "111a82d949bb949c396a69a08828781314d3afe779c07e9754c3b2834dfca415",
    "segments": "56052b2ddd35de483ec00a29a7f0ffc37e162b0ee0249a9a435c8a8b2d8ffe0f",
    "eurostat": "75ca6cc130b3748f568196a9caf62889e9f096ec358f65223b4095ac070be0e7",
}

# Stretches of untagged source lines tagged here, from one vertex to another along the line. The Marnet line from
# the Caspian Sea to Taganrog Bay carries no passage, so its canal stretch, from the Volga at Volgograd to the Don
# near Kalach-na-Donu, is tagged volga_don. The lower Volga and lower Don stay untagged, so Astrakhan and Rostov
# remain reachable from their own seas when the canal is closed.
PASSAGE_STRETCHES = [
    {"passage": "volga_don", "start": [44.503624, 48.424983], "end": [43.694824, 48.654066], "edges": 7},
]

# Lines added from the Eurostat SeaRoute network, which searoute-py dropped. Each path runs along consecutive vertices
# of Eurostat lines. A path with a join continues from its last vertex to that existing Marnet vertex; a path without
# one starts on an earlier path. The Kiel Canal is the one Eurostat line tagged kiel, from Brunsbuettel on the Elbe to
# Kiel-Holtenau. The approaches are Eurostat's own lines from each canal end, joined to the nearest Marnet vertices:
# up and down the Elbe, and out to Kiel Bay. Husum, Toenning and Eckernfoerde have no lane in searoute-py and lie
# nearer the canal than any other Marnet vertex, so Eurostat's lanes to them are added too; without them they would
# snap to the canal.
EUROSTAT_LINES = [
    {
        "passage": "kiel",
        "path": [[9.13, 53.88725], [9.28, 53.98325], [9.339, 54.1385], [9.572, 54.208], [9.715, 54.32375],
                 [9.8025, 54.3635], [10.162375, 54.3665]],
    },
    {"passage": None, "path": [[9.13, 53.88725], [9.04275, 53.87475]], "join": [9.041942, 53.874321]},
    {"passage": None, "path": [[9.13, 53.88725], [9.2, 53.87675]], "join": [9.227347, 53.877053]},
    {
        "passage": None,
        "path": [[10.162375, 54.3665], [10.1893125, 54.389875], [10.239, 54.433], [10.26475, 54.4555]],
        "join": [10.310669, 54.454073],
    },
    {
        "passage": None,
        "path": [[10.26475, 54.4555], [10.27525, 54.4784375], [10.227, 54.51275], [10.1935625, 54.5103125],
                 [10.150625, 54.507125], [10.07875, 54.5025], [9.89725, 54.478], [9.8625, 54.4735]],
    },
    {
        "passage": None,
        "path": [[9.025, 54.473], [8.92625, 54.46325], [8.797437500000001, 54.443], [8.7465, 54.442],
                 [8.62525, 54.42625], [8.526062499999998, 54.3726875], [8.52825, 54.338], [8.524999999999999, 54.26875],
                 [8.5645, 54.23], [8.5735, 54.18775], [8.59, 54.10225], [8.624375, 54.026250000000005],
                 [8.67, 53.97075], [8.683, 53.928625], [8.7055, 53.8985]],
        "join": [8.745294, 53.909831],
    },
    {
        "passage": None,
        "path": [[8.9005, 54.265], [8.83575, 54.256625], [8.7838125, 54.1355], [8.786625, 54.053], [8.74575, 53.942],
                 [8.754999999999999, 53.8965]],
        "join": [8.745294, 53.909831],
    },
]
MAX_JOIN_KM = 5.0


def load(path: Path, kind: str) -> dict:
    content = path.read_bytes()
    actual = hashlib.sha256(content).hexdigest()
    if actual != EXPECTED_HASHES[kind]:
        raise ValueError(f"{kind} SHA-256 is {actual}, expected {EXPECTED_HASHES[kind]}")
    return json.loads(content)


def load_eurostat(path: Path) -> list[tuple[list[list[float]], str | None]]:
    """Returns every line in the Eurostat GeoPackage with its passage tag."""
    content = path.read_bytes()
    actual = hashlib.sha256(content).hexdigest()
    if actual != EXPECTED_HASHES["eurostat"]:
        raise ValueError(f"eurostat SHA-256 is {actual}, expected {EXPECTED_HASHES['eurostat']}")
    with contextlib.closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as connection:
        rows = connection.execute("SELECT geometry, pass FROM type ORDER BY fid").fetchall()
    return [(geopackage_line(blob), passage or None) for blob, passage in rows]


def geopackage_line(blob: bytes) -> list[list[float]]:
    """Decodes a GeoPackage binary LineString: a header with an optional envelope, then well-known binary."""
    if blob[:2] != b"GP":
        raise ValueError("not a GeoPackage geometry")
    envelope = (0, 32, 48, 48, 64)[(blob[3] >> 1) & 7]
    wkb = blob[8 + envelope:]
    order = "<" if wkb[0] == 1 else ">"
    kind, count = struct.unpack(order + "II", wkb[1:9])
    if kind != 2:
        raise ValueError(f"unsupported WKB geometry type {kind}")
    return [list(struct.unpack(order + "dd", wkb[9 + 16 * i:25 + 16 * i])) for i in range(count)]


Line = list[list[float]]


def eurostat_paths(source: list[tuple[Line, str | None]], nodes: set[tuple[float, float]]) -> list[tuple[Line, str | None]]:
    """Checks each added path against the Eurostat network and returns it with its join to Marnet."""
    segments = {}
    for line, passage in source:
        for a, b in zip(line, line[1:]):
            segments[(tuple(a), tuple(b))] = segments[(tuple(b), tuple(a))] = passage
    result = []
    added_vertices: set[tuple[float, float]] = set()
    for added in EUROSTAT_LINES:
        path, passage = added["path"], added["passage"]
        if result and "join" not in added and tuple(path[0]) not in added_vertices:
            raise ValueError(f"{path[0]} is neither on an earlier added path nor joined to Marnet")
        added_vertices.update(map(tuple, path))
        for a, b in zip(path, path[1:]):
            key = (tuple(a), tuple(b))
            if key not in segments:
                raise ValueError(f"{a} to {b} is not a segment of the Eurostat network")
            if segments[key] != passage:
                raise ValueError(f"{a} to {b} is tagged {segments[key]} in Eurostat, expected {passage}")
        if passage is not None:
            tagged = [line for line, tag in source if tag == passage]
            if tagged not in ([path], [path[::-1]]):
                raise ValueError(f"{passage}: expected the path to be the one Eurostat line tagged {passage}")
        if "join" in added:
            join = added["join"]
            if tuple(join) not in nodes:
                raise ValueError(f"join {join} is not a Marnet vertex")
            if distance_km(tuple(path[-1]), tuple(join)) > MAX_JOIN_KM:
                raise ValueError(f"join {join} is more than {MAX_JOIN_KM} km from {path[-1]}")
            path = [*path, join]
        result.append((path, passage))

    # Each end of a tagged path must reach Marnet over untagged added paths, or the passage would be a dead end.
    untagged: dict[tuple[float, float], set[tuple[float, float]]] = {}
    for path, passage in result:
        if passage is None:
            for a, b in zip(map(tuple, path), map(tuple, path[1:])):
                untagged.setdefault(a, set()).add(b)
                untagged.setdefault(b, set()).add(a)
    for path, passage in result:
        if passage is None:
            continue
        for end in (tuple(path[0]), tuple(path[-1])):
            seen, frontier = {end}, [end]
            while frontier and not seen & nodes:
                frontier = [n for vertex in frontier for n in untagged.get(vertex, ()) if n not in seen]
                seen.update(frontier)
            if not seen & nodes:
                raise ValueError(f"{passage}: the end at {list(end)} does not reach a Marnet vertex")
    return result


def lines(feature: dict) -> list[list[list[float]]]:
    geometry = feature["geometry"]
    if geometry["type"] == "LineString":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiLineString":
        return geometry["coordinates"]
    raise ValueError(f"unsupported geometry {geometry['type']}")


def stretch_passages(line: list[list[float]], passage: str | None, matched: list[int]) -> list[str | None]:
    """Returns the passage of each segment in the line, applying any stretch that lies on it."""
    passages = [passage] * (len(line) - 1)
    for index, stretch in enumerate(PASSAGE_STRETCHES):
        if stretch["start"] not in line or stretch["end"] not in line:
            continue
        if passage is not None:
            raise ValueError(f"{stretch['passage']}: the source line is already tagged {passage}")
        start, end = sorted((line.index(stretch["start"]), line.index(stretch["end"])))
        if end - start != stretch["edges"]:
            raise ValueError(f"{stretch['passage']}: expected {stretch['edges']} segments, found {end - start}")
        passages[start:end] = [stretch["passage"]] * (end - start)
        matched[index] += 1
    return passages


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
    parser.add_argument("eurostat", type=Path, help="Eurostat SeaRoute marnet_plus_5km.gpkg")
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

    def add_segment(start: list[float], end: list[float], passage: str | None) -> None:
        u = node_id(start)
        v = node_id(end)
        weight = round(distance_km(tuple(start), tuple(end)), 1)
        edges.append([u, v, weight, passage])
        edges.append([v, u, weight, passage])

    matched = [0] * len(PASSAGE_STRETCHES)
    for source in (load(args.segments, "segments"), load(args.marnet, "marnet")):
        for feature in source["features"]:
            passage = feature.get("properties", {}).get("passage")
            for line in lines(feature):
                for start, end, segment_passage in zip(line, line[1:], stretch_passages(line, passage, matched)):
                    add_segment(start, end, segment_passage)

    for stretch, count in zip(PASSAGE_STRETCHES, matched):
        if count != 1:
            raise ValueError(f"{stretch['passage']}: expected the stretch on one source line, found {count}")

    # Added after every searoute-py line, so the existing nodes and edges keep their positions in the file.
    for path, passage in eurostat_paths(load_eurostat(args.eurostat), set(node_ids)):
        for start, end in zip(path, path[1:]):
            add_segment(start, end, passage)

    encoded = json.dumps({"nodes": nodes, "edges": edges}, separators=(",", ":")).encode()
    with args.output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as target:
            target.write(encoded)
    print(f"marnet: {len(nodes)} nodes, {len(edges)} directed edges; embedded SHA-256: {hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
