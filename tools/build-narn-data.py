#!/usr/bin/env python3
"""Build the deterministic compact rail graph from a BTS NTAD North American Rail Network Lines snapshot.

Keeps the main sub-network (NET "M"), joins arcs into chains between junctions, line ends and changes of owner,
simplifies each chain's line and writes it as consecutive graph edges. Each chain keeps its published length: the
chain's summed NARN KM is shared across its simplified segments in proportion to their great-circle length.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
from collections import defaultdict
from pathlib import Path

EARTH_RADIUS_KM = 6371.0088
EXPECTED_HASH = "ca4bb82ec226ea8005d352803500a46272c1627a2e1e9a049475e0e41f1aa440"
# Douglas-Peucker tolerance for the drawn line, and the longest simplified segment, so a location is never far
# from a graph node along straight track. Both only shape geometry and snapping; lengths stay NARN's.
SIMPLIFY_TOLERANCE_KM = 0.25
MAX_SEGMENT_KM = 10.0
# Main-line fragments below this length are islands NARN joins only through yard or industry track. Snapping a
# location onto one would strand the route, so they are dropped.
MIN_COMPONENT_KM = 100.0


def distance_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    lon1, lat1 = map(math.radians, a)
    lon2, lat2 = map(math.radians, b)
    value = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return EARTH_RADIUS_KM * 2 * math.atan2(math.sqrt(value), math.sqrt(max(0, 1 - value)))


def line_of(feature: dict) -> list[tuple[float, float]]:
    geometry = feature["geometry"]
    parts = [geometry["coordinates"]] if geometry["type"] == "LineString" else geometry["coordinates"]
    points: list[tuple[float, float]] = []
    for part in parts:
        for position in part:
            point = (position[0], position[1])
            if not points or points[-1] != point:
                points.append(point)
    return points


def offset_km(point, start, end) -> float:
    # Cross-track distance on a local equirectangular projection, accurate at chain-segment scale.
    scale = math.cos(math.radians((start[1] + end[1]) / 2))
    px, py = (point[0] - start[0]) * scale, point[1] - start[1]
    ex, ey = (end[0] - start[0]) * scale, end[1] - start[1]
    length = ex * ex + ey * ey
    t = 0.0 if length == 0 else max(0.0, min(1.0, (px * ex + py * ey) / length))
    dx, dy = px - t * ex, py - t * ey
    return math.radians(math.hypot(dx, dy)) * EARTH_RADIUS_KM


def simplify(points: list[tuple[float, float]]) -> list[tuple[float, float]]:
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        worst, index = 0.0, -1
        for i in range(first + 1, last):
            d = offset_km(points[i], points[first], points[last])
            if d > worst:
                worst, index = d, i
        if index >= 0 and (worst > SIMPLIFY_TOLERANCE_KM or distance_km(points[first], points[last]) > MAX_SEGMENT_KM):
            keep[index] = True
            stack.extend([(first, index), (index, last)])
    simplified = [point for point, kept in zip(points, keep) if kept]
    # A straight run longer than the segment limit has no vertex left to keep, so split it evenly.
    result = [simplified[0]]
    for start, end in zip(simplified, simplified[1:]):
        pieces = math.ceil(distance_km(start, end) / MAX_SEGMENT_KM)
        for k in range(1, pieces):
            result.append((start[0] + (end[0] - start[0]) * k / pieces, start[1] + (end[1] - start[1]) * k / pieces))
        result.append(end)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("lines", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    content = args.lines.read_bytes()
    actual = hashlib.sha256(content).hexdigest()
    if actual != EXPECTED_HASH:
        raise ValueError(f"NARN lines SHA-256 is {actual}, expected {EXPECTED_HASH}")
    features = json.loads(content)["features"]

    arcs = []
    for feature in features:
        properties = feature["properties"]
        if properties["NET"] != "M" or not feature.get("geometry"):
            continue
        points = line_of(feature)
        if len(points) < 2:
            continue
        arcs.append({
            "id": properties["FRAARCID"],
            "from": properties["FRFRANODE"],
            "to": properties["TOFRANODE"],
            "owner": (properties["RROWNER1"] or "").strip(),
            "km": float(properties["KM"] or 0.0),
            "points": points,
        })
    arcs.sort(key=lambda arc: arc["id"])

    # A node's position is where its arcs end; NARN draws each arc from its FRFRANODE to its TOFRANODE.
    position: dict[int, tuple[float, float]] = {}
    incident: dict[int, list[int]] = defaultdict(list)
    for index, arc in enumerate(arcs):
        position.setdefault(arc["from"], arc["points"][0])
        position.setdefault(arc["to"], arc["points"][-1])
        incident[arc["from"]].append(index)
        incident[arc["to"]].append(index)

    # Drop main-line fragments too short to route on.
    component: dict[int, int] = {}
    component_km: list[float] = []
    for start in sorted(incident):
        if start in component:
            continue
        label = len(component_km)
        component[start] = label
        stack, arc_ids = [start], set()
        while stack:
            node = stack.pop()
            for index in incident[node]:
                arc_ids.add(index)
                for neighbour in (arcs[index]["from"], arcs[index]["to"]):
                    if neighbour not in component:
                        component[neighbour] = label
                        stack.append(neighbour)
        component_km.append(sum(arcs[index]["km"] for index in arc_ids))
    kept_arcs = [i for i, arc in enumerate(arcs) if component_km[component[arc["from"]]] >= MIN_COMPONENT_KM]
    dropped_km = sum(arcs[i]["km"] for i in range(len(arcs))) - sum(arcs[i]["km"] for i in kept_arcs)
    kept = set(kept_arcs)
    incident = {node: [i for i in ids if i in kept] for node, ids in incident.items()}
    incident = {node: ids for node, ids in incident.items() if ids}

    def is_break(node: int) -> bool:
        ids = incident[node]
        return len(ids) != 2 or arcs[ids[0]]["owner"] != arcs[ids[1]]["owner"] or ids[0] == ids[1]

    # Walk chains of arcs between junctions, line ends and changes of owner.
    used: set[int] = set()
    chains = []

    def walk(node: int, index: int) -> None:
        points: list[tuple[float, float]] = [position[node]]
        km = 0.0
        owner = arcs[index]["owner"]
        while True:
            used.add(index)
            arc = arcs[index]
            forward = arc["from"] == node
            line = arc["points"] if forward else list(reversed(arc["points"]))
            points.extend(line[1:])
            km += arc["km"]
            node = arc["to"] if forward else arc["from"]
            if is_break(node):
                break
            index = next(i for i in incident[node] if i != index)
            if index in used:
                break
        points[-1] = position[node]
        chains.append((owner, km, points))

    for node in sorted(incident):
        if is_break(node):
            for index in incident[node]:
                if index not in used:
                    walk(node, index)
    # Closed loops with no junction on them.
    for index in kept_arcs:
        if index not in used:
            walk(arcs[index]["from"], index)

    owners = sorted({owner for owner, _, _ in chains})
    owner_index = {owner: i for i, owner in enumerate(owners)}
    nodes: list[list[float]] = []
    node_ids: dict[tuple[float, float], int] = {}
    edges: list[list[object]] = []

    def node_id(point: tuple[float, float]) -> int:
        key = (round(point[0], 5), round(point[1], 5))
        if key not in node_ids:
            node_ids[key] = len(nodes)
            nodes.append([*key])
        return node_ids[key]

    total_km = 0.0
    for owner, km, points in chains:
        line = simplify(points)
        segments = [distance_km(a, b) for a, b in zip(line, line[1:])]
        drawn = sum(segments)
        total_km += km
        for (a, b), segment in zip(zip(line, line[1:]), segments):
            u, v = node_id(a), node_id(b)
            if u == v:
                continue
            share = segment / drawn if drawn > 0 else 1.0 / len(segments)
            edges.append([u, v, round(km * share, 3), owner_index[owner]])

    encoded = json.dumps({"owners": owners, "nodes": nodes, "edges": edges}, separators=(",", ":")).encode()
    with args.output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as target:
            target.write(encoded)
    print(
        f"narn: {len(arcs)} main-line arcs, {len(kept_arcs)} kept in {sum(1 for km in component_km if km >= MIN_COMPONENT_KM)} "
        f"networks of {MIN_COMPONENT_KM:.0f} km or more ({dropped_km:.0f} km dropped); {len(chains)} chains, "
        f"{total_km:.0f} km; {len(nodes)} nodes, {len(edges)} edges, {len(owners)} owners; "
        f"embedded SHA-256: {hashlib.sha256(args.output.read_bytes()).hexdigest()}"
    )


if __name__ == "__main__":
    main()
