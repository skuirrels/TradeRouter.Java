#!/usr/bin/env python3
"""Download the BTS NTAD North American Rail Network Lines as one GeoJSON snapshot, ordered by OBJECTID.

The hub's cached GeoJSON lags the feature service, so the snapshot is paged from the service itself. The output is
deterministic for a given service state; build-narn-data.py pins the SHA-256 of the snapshot it was built from.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SERVICE = (
    "https://services.arcgis.com/xOi1kZaI0eWDREZv/ArcGIS/rest/services/"
    "NTAD_North_American_Rail_Network_Lines/FeatureServer/0/query"
)
FIELDS = "OBJECTID,FRAARCID,FRFRANODE,TOFRANODE,COUNTRY,STATEAB,RROWNER1,NET,KM"
PAGE = 2000


def fetch(last_id: int) -> dict:
    query = urllib.parse.urlencode({
        "where": f"OBJECTID>{last_id}",
        "outFields": FIELDS,
        "outSR": 4326,
        "geometryPrecision": 6,
        "orderByFields": "OBJECTID",
        "resultRecordCount": PAGE,
        "f": "geojson",
    })
    for attempt in range(8):
        try:
            with urllib.request.urlopen(f"{SERVICE}?{query}", timeout=120) as response:
                page = json.load(response)
            if "error" in page:
                raise RuntimeError(page["error"])
            return page
        except (urllib.error.URLError, RuntimeError, TimeoutError) as error:
            # The service allows 14,400 request units a minute and answers 429 beyond that.
            print(f"OBJECTID>{last_id}: {error}; retrying in 60 s")
            time.sleep(60)
    raise RuntimeError(f"OBJECTID>{last_id}: gave up after repeated failures")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    features: list[dict] = []
    last_id = 0
    while True:
        page = fetch(last_id)["features"]
        if not page:
            break
        features.extend(page)
        last_id = page[-1]["properties"]["OBJECTID"]
        print(f"{len(features)} features")

    features.sort(key=lambda feature: feature["properties"]["OBJECTID"])
    encoded = json.dumps({"type": "FeatureCollection", "features": features}, separators=(",", ":")).encode()
    args.output.write_bytes(encoded)
    print(f"{args.output}: {len(features)} features; SHA-256 {hashlib.sha256(encoded).hexdigest()}")


if __name__ == "__main__":
    main()
