#!/usr/bin/env python3
"""Fill coordinates for UN/LOCODE sea ports that no embedded source can place.

A sea-port code (function 1) qualifies when UNECE publishes no coordinate for it, the coordinate supplement
holds no other row for it and the port list does not hold it. Candidate positions come from five sources, each
pinned by SHA-256 in tools/sea-port-coordinate-sources.json:

- the UNECE 2025-1 release, for codes it has since given a coordinate (minutes of 60 or more are rejected),
- the NGA World Port Index (Pub. 150), by its UN/LOCODE column or by port name in the same country,
- Wikidata items carrying the code as UN/LOCODE (P1937) with a coordinate (P625),
- GeoNames features linked to the code by an alternate name in the `unlc` language, and
- GeoNames features in the same country whose name matches the port's own name.

A candidate must carry the port's name. GeoNames and Wikidata candidates must also lie in the code's UN/LOCODE
subdivision when both sides can be compared; World Port Index candidates linked by code are trusted as port
data. A code is placed when, in order:

1. two different sources agree within 15 km, at least one linked by code or from the World Port Index, and the
   position is within 20 km of the Natural Earth 10m coastline, or comes from the World Port Index, or both
   candidates are confirmed in the code's subdivision;
2. its code-linked candidates agree within 15 km and the best is within 20 km of the coastline, is a World Port
   Index port, or is confirmed in the code's subdivision. A GeoNames link that the subdivision does not confirm
   is not enough when another coastal place whose main name is the port's lies more than 15 km away, unless the text after
   the name, such as Cebu in Toledo/Cebu, names a place or region near the linked feature and near none of the others;
3. exactly one World Port Index port in the country carries the name, within 20 km of the coastline; or
4. every GeoNames feature of that name in the country lies within 10 km of the others, and a settlement or port
   among them is within 10 km of the coastline.

Codes placed within 2 km of another placed code in the same country are dropped unless their own candidate is
linked by code, because two codes rarely share a position and one of them is usually a namesake. The preferred
position is the World Port Index, then UNECE, Wikidata and GeoNames. No position is inferred from a parent city
named after a slash or comma, which UN/LOCODE uses for the city a port serves.

Rows written by this script start their source with "Port fill, ", so a rerun replaces them. Placed codes are
removed from the review list; a rerun with stricter rules does not restore them there.
"""

import argparse
import collections
import csv
import gzip
import hashlib
import io
import json
import math
import re
import unicodedata
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "traderouter/src/main/resources/io/github/skuirrels/traderouter/data"
MANIFEST = Path(__file__).with_name("sea-port-coordinate-sources.json")
MARKER = "Port fill, "
PORTISH = {"PRT", "HBR", "PIER", "LDNG", "MAR", "DCKY", "ANCH", "RDST", "WHRF", "DCK", "OILT", "TRMO"}
STRIP = r"\b(of|port|pt|harbou?r|terminal|oil terminal|anchorage|island|is|isl|pier|wharf|ko|kou|minato)\b"
PRIORITY = ["wpi_code", "wpi_name", "unece", "wd_code", "gn_link", "gn_name"]
FAMILY = {"unece": "unece", "wpi_code": "wpi", "wpi_name": "wpi", "wd_code": "wd", "gn_link": "gn", "gn_name": "gn"}
LINKED = {"wpi_code", "wd_code", "gn_link", "unece"}
WPI_COUNTRIES = {
    "United States": "US", "United Kingdom": "GB", "Korea, South": "KR", "Korea, North": "KP", "Russia": "RU",
    "Burma": "MM", "Vietnam": "VN", "Turkey": "TR", "Curacao": "CW", "Virgin Islands": "VI",
    "British Virgin Islands": "VG", "Cabo Verde": "CV", "Timor-Leste": "TL", "Macau": "MO", "Hong Kong": "HK",
    "Taiwan": "TW", "Iran": "IR", "Syria": "SY", "Brunei": "BN", "Sint Maarten": "SX", "Netherlands": "NL",
    "The Bahamas": "BS", "The Gambia": "GM", "Saint Helena, Ascension, and Tristan da Cunha": "SH",
    "Congo (Brazzaville)": "CG", "Congo (Kinshasa)": "CD", "Federated States of Micronesia": "FM",
    "Caribbean Netherlands": "BQ", "South Georgia and South Sandwich Islands": "GS", "Svalbard": "SJ",
    "Falkland Islands (Islas Malvinas)": "FK", "Saint Vincent and the Grenadines (Windward Islands)": "VC",
}
KUNREI = [("sy", "sh"), ("ty", "ch"), ("zy", "j"), ("si", "shi"), ("ti", "chi"), ("tu", "tsu"), ("hu", "fu"), ("zi", "ji")]


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def norm(text):
    ascii_text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii").lower()
    return re.sub(r"[^a-z0-9]", "", ascii_text)


def region_norm(text):
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii").lower()
    text = re.sub(r"\[.*?\]|\(.*?\)", "", text)
    text = re.sub(r"\b(prefecture|ken|to|fu|do|province|provincia|state|county|region|district|shi|sheng|zizhiqu"
                  r"|city|of|the|council|borough|municipality|kommune|amt|lan)\b", "", text)
    return re.sub(r"[^a-z0-9]", "", text)


def hepburn(text):
    for kunrei, hep in KUNREI:
        text = text.replace(kunrei, hep)
    return text.replace("shhi", "shi").replace("chhi", "chi")


def name_forms(parts):
    forms = set()
    for part in parts:
        if not part:
            continue
        forms.add(norm(part))
        stripped = norm(re.sub(STRIP, "", part.lower()))
        if stripped:
            forms.add(stripped)
    return {form for form in forms if len(form) >= 3}


def all_names(name):
    """Every part of a UN/LOCODE name, used to confirm a candidate linked by code."""
    parts = {name}
    for separator in ["/", ",", "("]:
        for part in list(parts):
            parts |= {piece.strip(" )") for piece in part.split(separator)}
    return name_forms(parts)


def own_names(name):
    """The port's own name and bracketed alternates, used to search by name. Text after a slash or comma names
    the city or region the port serves and is not searched."""
    alternates = [re.sub(r"^(ex|formerly)\s+", "", a.strip(), flags=re.I) for a in re.findall(r"\(([^)]*)\)", name)]
    base = re.split(r"[/,]", re.sub(r"\([^)]*\)", "", name))[0].strip()
    return name_forms([base, *alternates])


def qualifiers(name):
    """The text after a slash or comma: the city, island or region a port is filed under."""
    parts = re.split(r"[/,]", re.sub(r"\([^)]*\)", "", name))[1:]
    return {region_norm(p) for p in parts if len(region_norm(p)) >= 3}


def km(a, b):
    lon1, lat1, lon2, lat2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 12742 * math.asin(math.sqrt(h))


class Coastline:
    def __init__(self, path, cell=0.5):
        self.cell = cell
        self.grid = collections.defaultdict(list)
        for feature in json.loads(path.read_text())["features"]:
            geometry = feature["geometry"]
            lines = geometry["coordinates"] if geometry["type"] == "MultiLineString" else [geometry["coordinates"]]
            for line in lines:
                for a, b in zip(line, line[1:]):
                    for i in range(math.floor(min(a[0], b[0]) / cell), math.floor(max(a[0], b[0]) / cell) + 1):
                        for j in range(math.floor(min(a[1], b[1]) / cell), math.floor(max(a[1], b[1]) / cell) + 1):
                            self.grid[(i, j)].append((a, b))

    def distance_km(self, point):
        """Distance to the nearest coastline segment within about a degree; None beyond that."""
        ci, cj = math.floor(point[0] / self.cell), math.floor(point[1] / self.cell)
        ky = 111.32
        kx = ky * math.cos(math.radians(point[1]))
        best = None
        for i in range(ci - 2, ci + 3):
            for j in range(cj - 2, cj + 3):
                for a, b in self.grid.get((i, j), ()):
                    ax, ay = (a[0] - point[0]) * kx, (a[1] - point[1]) * ky
                    dx, dy = (b[0] - a[0]) * kx, (b[1] - a[1]) * ky
                    length = dx * dx + dy * dy
                    t = 0 if length == 0 else max(0, min(1, -(ax * dx + ay * dy) / length))
                    d = math.hypot(ax + t * dx, ay + t * dy)
                    best = d if best is None else min(best, d)
        return best


def near_coast(distance, limit):
    return distance is not None and distance <= limit


def parse_unece_coordinate(text):
    m = re.fullmatch(r"(\d{2})(\d{2})([NS]) (\d{3})(\d{2})([EW])", text.strip())
    if not m or int(m[2]) >= 60 or int(m[5]) >= 60:
        return None
    lat = int(m[1]) + int(m[2]) / 60
    lon = int(m[4]) + int(m[5]) / 60
    return (-lon if m[6] == "W" else lon, -lat if m[3] == "S" else lat)


class Subdivisions:
    def __init__(self, unece_rows, subdivision_rows, geonames):
        self.of_code = {code: row[5] for code, row in unece_rows.items() if row[5]}
        self.names = {(row[0], row[1]): row[2] for row in subdivision_rows if len(row) > 2}
        self.admin1, self.admin2, self.admin1_id, self.alternates = {}, {}, {}, {}
        for line in (geonames / "admin1CodesASCII.txt").read_text(encoding="utf-8").splitlines():
            key, name, ascii_name, geoname_id = line.split("\t")
            self.admin1[key] = {region_norm(name), region_norm(ascii_name)}
            self.admin1_id[key] = geoname_id
        for line in (geonames / "admin2Codes.txt").read_text(encoding="utf-8").splitlines():
            key, name, ascii_name, _ = line.split("\t")
            self.admin2[key] = {region_norm(name), region_norm(ascii_name)}

    def add_region(self, row):
        self.alternates[row[0]] = {region_norm(row[1]), region_norm(row[2])} | {region_norm(a) for a in row[3].split(",") if a}

    def expected(self, code):
        name = self.names.get((code[:2], self.of_code.get(code, "")))
        if not name:
            return set()
        raw = [name, *re.findall(r"\[(.*?)\]", name)]
        forms = {region_norm(x) for x in raw}
        if code.startswith("JP"):
            forms |= {region_norm(hepburn(x.lower())) for x in raw}
        return {form for form in forms if form}

    def check(self, code, admin1, admin2):
        """True when the GeoNames admin codes name the code's subdivision, False when they name another, None
        when either side is unknown."""
        expected = self.expected(code)
        if not expected or admin1 is None:
            return None
        country = code[:2]
        names = self.admin1.get(f"{country}.{admin1}", set()) | self.admin2.get(f"{country}.{admin1}.{admin2}", set())
        names |= self.alternates.get(self.admin1_id.get(f"{country}.{admin1}"), set())
        names.discard("")
        if not names:
            return None
        return any(e == n or (len(min(e, n, key=len)) >= 6 and (e in n or n in e)) for e in expected for n in names)


def load_manifest(inputs):
    manifest = json.loads(MANIFEST.read_text())
    for relative, expected in manifest["files"].items():
        path = inputs / relative
        if not path.exists():
            raise SystemExit(f"Missing input {path}")
        if digest(path) != expected:
            raise SystemExit(f"{relative} differs from the pinned {manifest['retrieved']} snapshot; review it before updating the manifest")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", type=Path, help="folder holding the files named in sea-port-coordinate-sources.json")
    parser.add_argument("--report", type=Path, help="write every decision and its evidence as JSON")
    args = parser.parse_args()
    load_manifest(args.inputs)

    with gzip.open(DATA / "unlocode.json.gz", "rt") as source:
        rows = json.load(source)
    with gzip.open(DATA / "unlocode-supplement.json.gz", "rt") as source:
        supplement = json.load(source)
    with gzip.open(DATA / "ports.json.gz", "rt") as source:
        port_codes = {port["port"] for port in json.load(source)}
    review_path = ROOT / "docs/unlocode-unresolved.csv"
    review = list(csv.DictReader(review_path.open()))

    kept = [row for row in supplement if not row["source"].startswith(MARKER)]
    supplemented = {row["code"] for row in kept}
    missing = {r[0]: r for r in rows if r[4] & 1 and r[2] is None and r[0] not in supplemented and r[0] not in port_codes}

    geonames = args.inputs / "geonames"
    linked_ids = collections.defaultdict(list)
    for country in sorted({code[:2] for code in missing}):
        archive_path = geonames / "alternatenames" / f"{country}.zip"
        if archive_path.exists():
            with zipfile.ZipFile(archive_path) as archive:
                for line in archive.open(f"{country}.txt"):
                    x = line.decode("utf-8").split("\t")
                    if len(x) > 3 and x[2] == "unlc" and x[3] in missing:
                        linked_ids[x[3]].append(x[1])

    unece_rows = {}
    with zipfile.ZipFile(args.inputs / "unece-2025-1.zip") as archive:
        for name in sorted(n for n in archive.namelist() if re.search(r"csv/UNLOCODE CodeListPart\d\.csv$", n)):
            for row in csv.reader(io.TextIOWrapper(archive.open(name), encoding="utf-8")):
                if len(row) > 10 and row[1] + row[2] in missing:
                    unece_rows[row[1] + row[2]] = row
        subdivision_rows = list(csv.reader(io.TextIOWrapper(archive.open("release/csv/SubdivisionCodes.csv"), encoding="utf-8")))

    regions = Subdivisions(unece_rows, subdivision_rows, geonames)
    coast = Coastline(args.inputs / "ne_10m_coastline.geojson")

    countries = {}
    for line in (geonames / "countryInfo.txt").read_text(encoding="utf-8").splitlines():
        if not line.startswith("#"):
            fields = line.split("\t")
            countries[norm(fields[4])] = fields[0]
    countries.update({norm(name): code for name, code in WPI_COUNTRIES.items()})
    wpi = collections.defaultdict(list)
    with (args.inputs / "UpdatedPub150.csv").open(encoding="utf-8-sig") as source:
        for row in csv.DictReader(source):
            country = countries.get(norm(row["Country Code"]))
            if not country:
                continue
            names = [row["Main Port Name"], *re.split(r"[;,]", row["Alternate Port Name"])]
            wpi[country].append({
                "forms": set().union(*(all_names(n) for n in names if n.strip())),
                "position": (float(row["Longitude"]), float(row["Latitude"])),
                "label": f"World Port Index {row['World Port Index Number'].split('.')[0]} {row['Main Port Name']}",
                "code": row["UN/LOCODE"].replace(" ", "").upper(),
            })
    wikidata = collections.defaultdict(list)
    with (args.inputs / "unlocode-coords.csv").open(encoding="utf-8") as source:
        for row in csv.DictReader(source):
            m = re.match(r"Point\(([-\d.eE]+) ([-\d.eE]+)\)", row.get("coord") or "")
            code = (row.get("code") or "").replace(" ", "").upper()
            if m and code in missing:
                qid = row["item"].rsplit("/", 1)[1]
                wikidata[code].append({"position": (float(m[1]), float(m[2])), "label": f"Wikidata {qid} {row['itemLabel']}", "names": row["itemLabel"]})

    decisions = {}
    for country in sorted({code[:2] for code in missing}):
        features, by_id = [], {}
        wanted = {i for code in missing if code[:2] == country for i in linked_ids.get(code, [])}
        archive_path = geonames / f"{country}.zip"
        if archive_path.exists():
            with zipfile.ZipFile(archive_path) as archive:
                for line in archive.open(f"{country}.txt"):
                    x = line.decode("utf-8").rstrip("\n").split("\t")
                    if x[6] == "A" and x[7] == "ADM1":
                        regions.add_region(x)
                    if x[0] in wanted:
                        by_id[x[0]] = x
                    if x[6] and x[6] in "PTHLS":
                        forms = {norm(x[1]), norm(x[2])} | {norm(a) for a in x[3].split(",") if a}
                        features.append((forms, (float(x[5]), float(x[4])), x))
        settlements = [f for f in features if f[2][6] == "P"]

        def admin_near(position):
            best = None
            for _, at, x in settlements:
                if abs(at[0] - position[0]) <= 0.15 and abs(at[1] - position[1]) <= 0.15:
                    d = km(position, at)
                    if d <= 10 and (best is None or d < best[0]):
                        best = (d, x)
            return (best[1][10], best[1][11]) if best else (None, None)

        def qualified(position, name):
            """Whether the text after the port's own name, such as Cebu in Toledo/Cebu, names a place or region
            within 100 km of the position."""
            wanted = qualifiers(name)
            if not wanted:
                return False
            admin1, admin2 = admin_near(position)
            if wanted & (regions.admin1.get(f"{country}.{admin1}", set()) | regions.admin2.get(f"{country}.{admin1}.{admin2}", set())):
                return True
            return any(forms & wanted and km(at, position) <= 100 for forms, at, _ in features)

        for code in sorted(c for c in missing if c[:2] == country):
            name = missing[code][1]
            names, own = all_names(name), own_names(name)
            evidence = []

            def add(kind, position, label, named, admin=None):
                admin = admin or admin_near(position)
                evidence.append({"kind": kind, "position": position, "label": label, "named": named,
                                 "coast_km": coast.distance_km(position), "subdivision": regions.check(code, *admin)})

            if code in unece_rows:
                position = parse_unece_coordinate(unece_rows[code][10])
                if position:
                    add("unece", position, f"UNECE UN/LOCODE 2025-1 {unece_rows[code][10].strip()}", True)
            for port in wpi.get(country, []):
                if port["code"] == code:
                    add("wpi_code", port["position"], port["label"], bool(port["forms"] & names))
                elif port["forms"] & own and not port["code"].startswith(country):
                    add("wpi_name", port["position"], port["label"], True)
            for item in wikidata.get(code, []):
                add("wd_code", item["position"], item["label"], bool(all_names(item["names"]) & names))
            for geoname_id in linked_ids.get(code, []):
                x = by_id.get(geoname_id)
                if x and x[8] == country:
                    forms = {norm(x[1]), norm(x[2])} | {norm(a) for a in x[3].split(",") if a}
                    add("gn_link", (float(x[5]), float(x[4])), f"GeoNames {x[1]} geonameId {x[0]}", bool(forms & names), (x[10], x[11]))
            namesakes = [f for f in features if f[0] & own]
            for _, position, x in namesakes:
                if x[6] == "P" or x[7] in PORTISH:
                    add("gn_name", position, f"GeoNames {x[1]} geonameId {x[0]}", True, (x[10], x[11]))

            usable = [e for e in evidence if e["named"] and (e["kind"] == "wpi_code" or e["subdivision"] is not False)]
            decision = None
            for a in usable:
                if a["kind"] not in LINKED | {"wpi_name"}:
                    continue
                for b in usable:
                    if FAMILY[a["kind"]] == FAMILY[b["kind"]] or km(a["position"], b["position"]) > 15:
                        continue
                    if not (near_coast(a["coast_km"], 20) or near_coast(b["coast_km"], 20)
                            or "wpi" in (FAMILY[a["kind"]], FAMILY[b["kind"]]) or (a["subdivision"] and b["subdivision"])):
                        continue
                    group = [e for e in usable if km(e["position"], a["position"]) <= 15]
                    families = sorted({FAMILY[e["kind"]] for e in group})
                    if decision is None or len(families) > len(decision["families"]):
                        decision = {"rule": 1, "best": min(group, key=lambda e: PRIORITY.index(e["kind"])), "families": families}
            if decision is None:
                linked = [e for e in usable if e["kind"] in LINKED]
                if linked and max(km(a["position"], b["position"]) for a in linked for b in linked) <= 15:
                    best = min(linked, key=lambda e: PRIORITY.index(e["kind"]))
                    rivals = [p for _, p, x in namesakes if (x[6] == "P" or x[7] in PORTISH) and {norm(x[1]), norm(x[2])} & own
                              and km(p, best["position"]) > 15 and near_coast(coast.distance_km(p), 10)]
                    if best["kind"] == "gn_link" and not best["subdivision"] and rivals and not (
                            qualified(best["position"], name) and not any(qualified(p, name) for p in rivals)):
                        pass
                    elif near_coast(best["coast_km"], 20) or best["kind"] == "wpi_code" or best["subdivision"]:
                        decision = {"rule": 2, "best": best, "families": [FAMILY[best["kind"]]]}
            if decision is None:
                by_name = [e for e in usable if e["kind"] == "wpi_name"]
                if len(by_name) == 1 and near_coast(by_name[0]["coast_km"], 20):
                    decision = {"rule": 3, "best": by_name[0], "families": ["wpi"]}
            if decision is None:
                by_name = [e for e in usable if e["kind"] == "gn_name"]
                spread = [p for _, p, _ in namesakes]
                if by_name and max(km(a, b) for a in spread for b in spread) <= 10:
                    best = min(by_name, key=lambda e: e["coast_km"] if e["coast_km"] is not None else math.inf)
                    if near_coast(best["coast_km"], 10):
                        decision = {"rule": 4, "best": best, "families": ["gn"]}
            decisions[code] = {"name": name, "decision": decision, "evidence": evidence}

    placed = sorted(code for code, d in decisions.items() if d["decision"])
    for i, a in enumerate(placed):
        for b in placed[i + 1:]:
            if a[:2] == b[:2] and km(decisions[a]["decision"]["best"]["position"], decisions[b]["decision"]["best"]["position"]) <= 2:
                for code, other in ((a, b), (b, a)):
                    decisions[code].setdefault("near", []).append(other)
    for code in placed:
        if decisions[code].get("near") and decisions[code]["decision"]["best"]["kind"] not in {"wpi_code", "wd_code", "unece"}:
            decisions[code]["rejected"] = decisions[code]["decision"]
            decisions[code]["decision"] = None

    added = []
    for code in sorted(code for code, d in decisions.items() if d["decision"]):
        decision = decisions[code]["decision"]
        best = decision["best"]
        how = {"wpi_code": "linked by UN/LOCODE", "wd_code": "linked by UN/LOCODE (P1937)", "gn_link": "linked by UN/LOCODE",
               "unece": "a later UNECE release", "wpi_name": "matched by port name",
               "gn_name": f"the only place of that name in {code[:2]}"}[best["kind"]]
        notes = [f"{best['label']}, {how}"]
        others = [f for f in decision["families"] if f != FAMILY[best["kind"]]]
        if others:
            names = {"wpi": "the World Port Index", "wd": "Wikidata", "gn": "GeoNames", "unece": "UNECE 2025-1"}
            notes.append("agrees within 15 km with " + " and ".join(names[f] for f in others))
        if best["subdivision"]:
            notes.append("in its UN/LOCODE subdivision")
        if best["coast_km"] is not None and best["coast_km"] <= 20:
            notes.append(f"{best['coast_km']:.1f} km from the coastline")
        lon, lat = best["position"]
        added.append({"code": code, "name": missing[code][1], "lon": round(lon, 5), "lat": round(lat, 5),
                      "source": MARKER + "; ".join(notes)})

    combined = [row for row in kept if not row["source"].startswith("GeoNames (geonameId ")] + added
    combined += [row for row in kept if row["source"].startswith("GeoNames (geonameId ")]
    lines = ["  " + json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in combined]
    encoded = ("[\n" + ",\n".join(lines) + "\n]\n").encode()
    output = DATA / "unlocode-supplement.json.gz"
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as target:
            target.write(encoded)
    placed_codes = {row["code"] for row in added}
    with review_path.open("w", newline="") as target:
        writer = csv.writer(target, lineterminator="\n")
        writer.writerow(("code", "name", "reason", "geoname_ids"))
        writer.writerows((r["code"], r["name"], r["reason"], r["geoname_ids"]) for r in review if r["code"] not in placed_codes)
    if args.report:
        args.report.write_text(json.dumps(decisions, ensure_ascii=False, indent=1, default=list))

    rules = collections.Counter(decisions[c]["decision"]["rule"] for c in placed_codes)
    print(json.dumps({
        "candidates": len(missing), "placed": len(added), "by_rule": {str(k): rules[k] for k in sorted(rules)},
        "dropped_near_another": sum(1 for d in decisions.values() if d.get("rejected")),
        "by_source": collections.Counter(row["source"][len(MARKER):].split(" ")[0] for row in added),
        "supplement_sha256": digest(output), "supplement_json_sha256": hashlib.sha256(encoded).hexdigest(),
        "review_list_sha256": digest(review_path),
    }, indent=2))


if __name__ == "__main__":
    main()
