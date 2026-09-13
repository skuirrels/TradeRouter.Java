#!/usr/bin/env python3
"""Build the deterministic compact ports resource from searoute-py 1.6.0 GeoJSON."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path

EXPECTED_SOURCE_HASH = "8398cb9554a19cf37f53f932f3842f58fbd94fce4c8e2704c3f5a4205ea5977e"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    content = args.source.read_bytes()
    actual_hash = hashlib.sha256(content).hexdigest()
    if actual_hash != EXPECTED_SOURCE_HASH:
        raise ValueError(f"source SHA-256 is {actual_hash}, expected {EXPECTED_SOURCE_HASH}")

    source = json.loads(content)
    ports = []
    for feature in source["features"]:
        props = feature["properties"]
        longitude, latitude = feature["geometry"]["coordinates"]
        ports.append({
            "port": props.get("port", ""),
            "name": props.get("name", ""),
            "cty": props.get("cty", ""),
            "t": props.get("t") or 0.0,
            "to_cty": props.get("to_cty") or [],
            "x": longitude,
            "y": latitude,
        })

    encoded = json.dumps(ports, ensure_ascii=False, separators=(",", ":")).encode()
    with args.output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as target:
            target.write(encoded)
    print(f"ports: {len(ports)} records; embedded SHA-256: {hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
